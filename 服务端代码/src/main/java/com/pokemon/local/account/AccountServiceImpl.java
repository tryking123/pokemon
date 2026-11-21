package com.pokemon.local.account;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.annotation.PostConstruct;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import com.pokemon.local.account.api.entity.LocalAccount;
import com.pokemon.local.account.api.vo.LoginVO;
import com.pokemon.local.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 本地账号与会话 ServiceImpl
 *
 * @author tongruiyuan
 * @since 2026-11-18 17:03
 */
@Service
@DependsOnDatabaseInitialization
public class AccountServiceImpl implements AccountService {

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_ID = "1";
    private static final int HASH_ITERATIONS = 600_000;
    private static final int HASH_BITS = 256;
    private static final int MAX_PASSWORD_LENGTH = 256;
    private static final Pattern BEARER = Pattern.compile("Bearer [A-Za-z0-9_-]{43}", Pattern.CASE_INSENSITIVE);
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecureRandom random = new SecureRandom();
    private final AccountMapper accounts;
    private final String initialPassword;

    /**
     * 初始化本地账号业务
     *
     * @param accounts 账号和会话 Mapper
     * @param initialPassword 数据库首次初始化所用的账号密码
     */
    public AccountServiceImpl(AccountMapper accounts,
                              @Value("${POKEMON_ADMIN_PASSWORD:admin}") String initialPassword) {
        this.accounts = accounts;
        this.initialPassword = initialPassword;
    }

    /**
     * 在数据库首次初始化时创建默认管理员账号
     */
    @PostConstruct
    public void initializeAdmin() {
        if (accounts.findByUsername(ADMIN_USERNAME) != null) {
            return;
        }
        if (initialPassword.isEmpty() || initialPassword.length() > MAX_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("初始账号密码长度必须为 1 至 256 个字符");
        }
        byte[] salt = randomBytes(16);
        byte[] hash = derivePassword(initialPassword, salt, HASH_ITERATIONS);
        accounts.insertAccount(ADMIN_ID, ADMIN_USERNAME, HASH_ITERATIONS + "$"
                + ENCODER.encodeToString(salt) + "$" + ENCODER.encodeToString(hash));
    }

    /**
     * 验证账号凭据并签发有效期为 24 小时的令牌
     *
     * @param vo 本地账号登录凭据
     * @return 会话令牌和账号信息
     */
    @Override
    public Object login(LoginVO vo) {
        if (vo.username == null || vo.username.isEmpty() || vo.username.length() > 64
                || vo.password == null || vo.password.isEmpty() || vo.password.length() > MAX_PASSWORD_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CREDENTIALS", "账号或密码格式无效");
        }
        LocalAccount account = accounts.findByUsername(vo.username);
        if (account == null || !matches(vo.password, account.passwordHash)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", "账号或密码错误");
        }
        String token = ENCODER.encodeToString(randomBytes(32));
        long now = System.currentTimeMillis();
        accounts.deleteExpiredSessions(now);
        accounts.insertSession(tokenDigest(token), account.id, now + TimeUnit.HOURS.toMillis(24));
        Map<String, Object> result = accountView(account);
        result.put("token", token);
        return result;
    }

    /**
     * 删除当前令牌对应的会话
     *
     * @param authorization HTTP 授权请求头
     * @return 会话注销状态
     */
    @Override
    public Object logout(String authorization) {
        if (accounts.deleteActiveSession(authorizationDigest(authorization), System.currentTimeMillis()) == 0) {
            throw unauthorized();
        }
        return Collections.singletonMap("status", "LOGGED_OUT");
    }

    /**
     * 获取当前登录账号和钻石余额
     *
     * @param authorization HTTP 授权请求头
     * @return 账号信息和余额
     */
    @Override
    public Object me(String authorization) {
        return accountView(requireAccount(authorization));
    }

    /**
     * 查询当前有效会话对应的账号
     *
     * @param authorization HTTP 授权请求头
     * @return 当前登录账号
     */
    @Override
    public LocalAccount requireAccount(String authorization) {
        LocalAccount account = accounts.findActiveAccount(authorizationDigest(authorization), System.currentTimeMillis());
        if (account == null) {
            throw unauthorized();
        }
        return account;
    }

    /**
     * 将数据库账号转换为可公开的响应字段
     *
     * @param account 本地账号
     * @return 账号标识、用户名和余额
     */
    private Map<String, Object> accountView(LocalAccount account) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accountId", account.id);
        result.put("username", account.username);
        result.put("balance", account.balance);
        return result;
    }

    /**
     * 校验密码与保存的摘要是否匹配
     *
     * @param password 输入密码
     * @param stored 保存的迭代次数、盐值和摘要
     * @return 是否匹配
     */
    private boolean matches(String password, String stored) {
        String[] fields = stored.split("\\$", -1);
        byte[] expected = DECODER.decode(fields[2]);
        byte[] actual = derivePassword(password, DECODER.decode(fields[1]), Integer.parseInt(fields[0]));
        return MessageDigest.isEqual(expected, actual);
    }

    /**
     * 使用 PBKDF2 派生密码摘要
     *
     * @param password 输入密码
     * @param salt 每个账号独立的盐值
     * @param iterations 迭代次数
     * @return 派生后的字节数组
     */
    private byte[] derivePassword(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("无法计算密码摘要", e);
        } finally {
            spec.clearPassword();
        }
    }

    /**
     * 生成指定长度的安全随机数据
     *
     * @param length 字节数
     * @return 随机字节数组
     */
    private byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    /**
     * 提取授权令牌并计算摘要
     *
     * @param authorization HTTP 授权请求头
     * @return 令牌摘要
     */
    private String authorizationDigest(String authorization) {
        if (authorization == null || !BEARER.matcher(authorization).matches()) {
            throw unauthorized();
        }
        return tokenDigest(authorization.substring("Bearer ".length()));
    }

    /**
     * 计算会话令牌的 SHA-256 摘要
     *
     * @param token 公开会话令牌
     * @return URL 安全编码后的摘要
     */
    private String tokenDigest(String token) {
        try {
            return ENCODER.encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("无法计算令牌摘要", e);
        }
    }

    /**
     * 创建会话未授权异常
     *
     * @return 会话未授权异常
     */
    private ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "请先登录本地账号");
    }
}
