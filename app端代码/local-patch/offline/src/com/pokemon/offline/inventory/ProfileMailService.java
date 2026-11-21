package com.pokemon.offline.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.offline.account.AccountService;
import com.pokemon.offline.common.ApiException;
import com.pokemon.offline.inventory.api.entity.SystemMailReward;
import com.pokemon.offline.inventory.api.vo.ProfileMailVO;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.http.HttpStatus;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 个人物品邮件 Service，按物品目录向当前账号的指定角色寄送附件。
 *
 * @author tongruiyuan
 * @since 2026-11-20 13:36
 */
@Service
public class ProfileMailService {
    private final AccountService accounts;
    private final RoleService roles;
    private final InventoryMapper mail;
    private final Map<String,String> names = new HashMap<>();

    /**
     * 加载界面和邮件服务共用的可发放目录。
     * @param accounts 本地账号会话
     * @param roles 角色归属与事务锁
     * @param mail 系统邮箱
     * @param json 配置读取器
     * @throws IOException 随包目录无法读取
     */
    public ProfileMailService(AccountService accounts, RoleService roles, InventoryMapper mail, ObjectMapper json) throws IOException {
        this.accounts = accounts;
        this.roles = roles;
        this.mail = mail;
        try (InputStream input = new ClassPathResource("catalog/grant-mail-items.json").getInputStream()) {
            for (JsonNode row : json.readTree(input).required("items")) {
                if (row.path("grantable").asBoolean()) {
                    String key = row.required("type").asInt() + "/" + row.required("id").asInt();
                    String name = row.required("name").asText();
                    if ("贴纸".equals(row.path("category").asText())) {
                        name += " · 刻印等级 " + row.path("stickerLevel").asInt();
                    } else if (row.path("quality").asInt() > 0) {
                        name += " · " + row.path("quality").asInt() + "阶 " + row.path("gearKind").asText();
                        if (row.path("gearLevel").asInt() > 0) name += " · " + row.path("gearLevel").asInt() + "级";
                    }
                    names.put(key, name);
                }
            }
        }
    }

    /**
     * 验证授权、物品和数量，将一次确认写成一封可通过原邮箱领取的邮件。
     * @param authorization 本地账号会话请求头
     * @param vo 角色、物品、数量和请求编号
     * @return 持久化的邮件编号
     */
    @Transactional
    public Map<String,String> send(String authorization, ProfileMailVO vo) {
        String account = accounts.requireAccount(authorization).id;
        if (vo == null || vo.roleId == null || vo.requestId == null || !vo.requestId.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
                || vo.quantity < 1 || vo.quantity > Integer.MAX_VALUE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_GRANT", "请输入有效物品和正整数数量");
        }
        String key = vo.type + "/" + vo.itemId;
        String name = names.get(key);
        if (name == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_ITEM", "此物品无法通过邮箱发放");
        }
        LocalRole role = roles.findById(vo.roleId).filter(r -> account.equals(r.accountId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND", "当前账号未找到该角色"));
        roles.lockRole(role.id);
        String mailId = "grant-" + UUID.nameUUIDFromBytes((role.id + ":" + UUID.fromString(vo.requestId)).getBytes(StandardCharsets.UTF_8));
        String signature = "编号 " + key + " × " + vo.quantity;
        String contents = name + " × " + vo.quantity + "\n物品编号 " + key;
        List<String> previous = mail.grantMailBody(role.id, mailId);
        if (!previous.isEmpty()) {
            if (!previous.get(0).equals(contents) && !previous.get(0).endsWith("\n" + signature)) {
                throw new ApiException(HttpStatus.CONFLICT, "GRANT_CHANGED", "同一次发放的物品或数量已改变");
            }
            return Collections.singletonMap("mailId", mailId);
        }
        long now = Instant.now().getEpochSecond();
        boolean wallet = vo.type == 103 || vo.type == 104 || vo.type == 105;
        List<SystemMailReward> rewards = wallet ? Collections.emptyList() :
                Collections.singletonList(new SystemMailReward(vo.type, vo.itemId, (int)vo.quantity));
        mail.createMail(role.id, mailId, "物品发放 · " + name, contents, now, now + 30L * 86400, rewards);
        if (wallet) {
            mail.setGrantWallet(role.id, mailId, vo.type, vo.quantity);
        }
        return Collections.singletonMap("mailId", mailId);
    }
}
