package com.pokemon.offline.bootstrap;

import com.pokemon.offline.common.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import com.pokemon.offline.platform.beans.factory.annotation.Value;
import com.pokemon.offline.platform.http.HttpStatus;
import com.pokemon.offline.platform.stereotype.Component;

/**
 * 固定底包的 Unity 资源索引，按需解包并校验客户端允许下载的文件
 *
 * @author tongruiyuan
 * @since 2026-11-18 17:43
 */
@Component
class BootstrapAssets {

    private static final String BASE_SHA256 = "f010a675dcb9ccacf899a2954e7e497be7f04c3ea94214140fa3434c817a7c45";
    private static final String INDEX_SHA256 = "3168100fe33b1270ef057837a89fbb1193817a5fa70e510ecc6010ea8ffc11ff";
    private static final String INSTALLED_INDEX_DIGEST = "assets/pokemon-local/unity-index.txt";
    private static final String APK_ASSETS = "assets/assets/";
    private static final String ASSET_MANIFEST = "version.txt";
    private static final String DATA_MANIFEST = "xversion.txt";
    private static final String PASSWORD = ";Oiz8123*as87122(*(zhjkahdflksd))";
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    private final String configuredApk;
    private boolean installedApk;
    private Catalog catalog;

    /**
     * 初始化底包读取器。
     *
     * @param configuredApk 可选的底包路径配置
     */
    BootstrapAssets(@Value("${POKEMON_BOOTSTRAP_APK:}") String configuredApk) {
        this.configuredApk = configuredApk;
    }

    /**
     * 为 APK 内运行环境读取自身签名包，使用固定底包资源清单逐项验算。
     *
     * @param apkPath 当前已安装 APK 路径
     * @return 同一份游戏资源读取器
     */
    static BootstrapAssets fromInstalledApk(String apkPath) {
        BootstrapAssets embedded = new BootstrapAssets(apkPath);
        embedded.installedApk = true;
        return embedded;
    }

    /**
     * 导出通过清单校验的 Unity 资源供 file URI 直接读取。
     * @param root App 私有资源目录
     * @throws IOException 资源校验或文件写入失败
     */
    synchronized void export(java.io.File root) throws IOException {
        try {
            Catalog current = catalog();
            String version = hex(MessageDigest.getInstance("SHA-256").digest(
                    (hex(current.assetManifest) + hex(current.dataManifest)).getBytes(StandardCharsets.US_ASCII)));
            java.io.File marker = new java.io.File(root, "resources-ready.txt");
            if (marker.isFile() && new String(Files.readAllBytes(marker.toPath()), StandardCharsets.US_ASCII).equals(version)) return;
            for (boolean data : new boolean[]{false, true}) {
                java.io.File folder = new java.io.File(root, data ? "data" : "resources/android");
                if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("无法创建 App 资源目录");
                Map<String, Asset> files = data ? current.data : current.assets;
                for (String name : files.keySet()) {
                    java.io.File output = new java.io.File(folder, name);
                    if (!output.getCanonicalFile().getParentFile().equals(folder.getCanonicalFile())) throw new IOException("资源路径不属于 App 缓存");
                    byte[] content = read(data, name);
                    java.io.File temporary = java.io.File.createTempFile("resource-", ".tmp", folder);
                    try {
                        try (java.io.FileOutputStream stream = new java.io.FileOutputStream(temporary)) {
                            stream.write(content);
                            stream.getFD().sync();
                        }
                        Files.move(temporary.toPath(), output.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } finally { Files.deleteIfExists(temporary.toPath()); }
                }
                Files.write(new java.io.File(folder, data ? DATA_MANIFEST : ASSET_MANIFEST).toPath(),
                        data ? current.dataManifest : current.assetManifest);
            }
            Files.write(marker.toPath(), version.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException failure) {
            throw new IOException("无法校验 App 资源", failure);
        }
    }

    /**
     * 读取清单或归属于当前清单的文件。
     *
     * @param data 是否查询数据清单
     * @param name 客户端请求的逻辑文件名
     * @return 解密后且通过长度和摘要校验的资源字节
     */
    byte[] read(boolean data, String name) {
        try {
            Catalog current = catalog();
            if (data && DATA_MANIFEST.equals(name)) {
                return current.dataManifest.clone();
            }
            if (!data && ASSET_MANIFEST.equals(name)) {
                return current.assetManifest.clone();
            }
            Asset asset = (data ? current.data : current.assets).get(name);
            if (asset == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "UNITY_RESOURCE_NOT_FOUND", "清单中没有该资源");
            }
            try (ZipFile zip = new ZipFile(current.apk.toFile())) {
                byte[] decoded = readSection(zip, asset.section);
                if (decoded.length != asset.size || !hex(MessageDigest.getInstance("MD5").digest(decoded)).equals(asset.md5)) {
                    throw new IOException("逻辑资源大小或 MD5 不符：" + name);
                }
                return decoded;
            }
        } catch (IOException | GeneralSecurityException exception) {
            ApiException error = new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "UNITY_RESOURCES_UNAVAILABLE", "无法读取已校验的游戏底包资源，请检查 POKEMON_BOOTSTRAP_APK");
            error.initCause(exception);
            throw error;
        }
    }

    /**
     * 首次访问时加载固定底包索引。
     *
     * @return 经过校验的两份资源清单及文件映射
     * @throws IOException 底包、映射或清单不符合预期
     * @throws GeneralSecurityException 摘要计算或资源解密失败
     */
    private synchronized Catalog catalog() throws IOException, GeneralSecurityException {
        if (catalog != null) {
            return catalog;
        }
        Path apk = locateApk().toAbsolutePath().normalize();
        if (!installedApk) {
            try (InputStream input = Files.newInputStream(apk)) {
                verifyDigest(input, BASE_SHA256);
            }
        }
        try (ZipFile zip = new ZipFile(apk.toFile())) {
            ZipEntry index = zip.getEntry(APK_ASSETS + "manifest.ab");
            if (index == null || index.getSize() < 16 || index.getSize() > Integer.MAX_VALUE) {
                throw new IOException("底包资源映射不存在");
            }
            if (installedApk) {
                try (InputStream input = zip.getInputStream(index)) {
                    verifyDigest(input, installedIndexDigest(zip));
                }
            }
            byte[] decoded = readSection(zip, new Section("manifest.ab", 0, (int) index.getSize()));
            Map<String, Section> sections = parseIndex(decoded);
            byte[] assets = readSection(zip, sections.get(ASSET_MANIFEST));
            byte[] data = readSection(zip, sections.get(DATA_MANIFEST));
            catalog = new Catalog(apk, assets, data, parseManifest(assets, sections), parseManifest(data, sections));
            return catalog;
        }
    }

    /**
     * 读取打包时写入同一签名 APK 的物理资源清单摘要，兼容未替换清单的版本。
     *
     * @param zip 当前已安装的签名 APK
     * @return 摘要文件内的 SHA-256 或固定底包摘要
     * @throws IOException 摘要条目长度或格式错误
     */
    private String installedIndexDigest(ZipFile zip) throws IOException {
        ZipEntry digest = zip.getEntry(INSTALLED_INDEX_DIGEST);
        if (digest == null) {
            return INDEX_SHA256;
        }
        if (digest.getSize() != 64) {
            throw new IOException("安装包资源清单摘要长度不符");
        }
        byte[] value = new byte[64];
        try (java.io.DataInputStream input = new java.io.DataInputStream(zip.getInputStream(digest))) {
            input.readFully(value);
        }
        String expected = new String(value, StandardCharsets.US_ASCII);
        if (!expected.matches("[0-9a-f]{64}")) {
            throw new IOException("安装包资源清单摘要格式不符");
        }
        return expected;
    }

    /**
     * 核对读取对象与固定底包摘要相符。
     *
     * @param input 当前读取的 APK 或资源清单
     * @param expected 原始 SHA-256
     * @throws IOException 读取失败或摘要不符
     * @throws GeneralSecurityException 摘要算法不可用
     */
    private void verifyDigest(InputStream input, String expected) throws IOException, GeneralSecurityException {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            sha256.update(buffer, 0, count);
        }
        if (!expected.equals(hex(sha256.digest()))) {
            throw new IOException("固定底包资源摘要不符");
        }
    }

    /**
     * 根据显式配置及工作目录查找固定底包。
     *
     * @return 底包路径
     * @throws IOException 未找到可读取的底包
     */
    private Path locateApk() throws IOException {
        if (!configuredApk.isEmpty()) {
            return Paths.get(configuredApk);
        }
        throw new IOException("必须从当前 App 安装包绑定离线资源");
    }

    /**
     * 解析底包中逻辑名到物理区段的映射。
     *
     * @param bytes 解密后的映射文本
     * @return 以逻辑名索引的区段
     * @throws IOException 索引格式异常
     */
    private Map<String, Section> parseIndex(byte[] bytes) throws IOException {
        Map<String, Section> items = new HashMap<>();
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            String[] columns = line.split("\\|", -1);
            if (columns.length != 4) {
                throw new IOException("底包资源映射行格式有误");
            }
            try {
                Section item = new Section(columns[1], Long.parseLong(columns[2]), Integer.parseInt(columns[3]));
                if (items.put(columns[0], item) != null) {
                    throw new IOException("底包资源映射含重复项目：" + columns[0]);
                }
            } catch (NumberFormatException exception) {
                throw new IOException("底包区段偏移或长度非数字", exception);
            }
        }
        return items;
    }

    /**
     * 解析客户端清单中允许下载的文件名、摘要和大小。
     *
     * @param bytes 原版客户端清单
     * @param sections 底包逻辑名到物理区段映射
     * @return 当前清单允许下载的文件
     * @throws IOException 清单行或映射关系无效
     */
    private Map<String, Asset> parseManifest(byte[] bytes, Map<String, Section> sections) throws IOException {
        Map<String, Asset> items = new HashMap<>();
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            String[] columns = line.split("\\|", -1);
            if (columns.length != 3 || !columns[1].matches("[a-f0-9]{32}")) {
                throw new IOException("Unity 版本清单行格式有误");
            }
            String name = columns[0];
            Section section = sections.get(name);
            if (section == null || !name.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*")) {
                throw new IOException("Unity 版本清单引用了不存在的逻辑文件：" + name);
            }
            try {
                int size = Integer.parseInt(columns[2]);
                if (size < 0 || size >= section.length || items.put(name, new Asset(section, columns[1], size)) != null) {
                    throw new IOException("Unity 逻辑资源大小异常或重复：" + name);
                }
            } catch (NumberFormatException exception) {
                throw new IOException("Unity 逻辑资源大小非数字", exception);
            }
        }
        return items;
    }

    /**
     * 从 ZIP 内的物理资源读取指定密文区段。
     *
     * @param zip 固定底包打开后的 ZIP 文件
     * @param section 逻辑文件对应密文区段
     * @return 解密并去填充的内容
     * @throws IOException ZIP 文件或区段不完整
     * @throws GeneralSecurityException 无法解密资源字节
     */
    private byte[] readSection(ZipFile zip, Section section) throws IOException, GeneralSecurityException {
        if (section == null) {
            throw new IOException("底包内无对应映射");
        }
        ZipEntry entry = zip.getEntry(APK_ASSETS + section.bundle);
        if (entry == null || section.offset < 0 || section.length < 16 || section.length % 16 != 0
                || section.offset > entry.getSize() - section.length) {
            throw new IOException("底包区段越界：" + section.bundle);
        }
        byte[] encrypted = new byte[section.length];
        try (InputStream input = zip.getInputStream(entry)) {
            long remaining = section.offset;
            while (remaining > 0) {
                long skipped = input.skip(remaining);
                if (skipped == 0) {
                    if (input.read() == -1) {
                        throw new IOException("无法定位底包资源区段：" + section.bundle);
                    }
                    skipped = 1;
                }
                remaining -= skipped;
            }
            for (int offset = 0; offset < encrypted.length;) {
                int count = input.read(encrypted, offset, encrypted.length - offset);
                if (count < 0) {
                    throw new IOException("底包资源区段不完整：" + section.bundle);
                }
                offset += count;
            }
        }
        byte[] rawKey = PASSWORD.getBytes(StandardCharsets.UTF_8);
        byte[] key = Arrays.copyOf(rawKey, 32);
        if (rawKey.length < key.length) {
            Arrays.fill(key, rawKey.length, key.length, (byte) 'X');
        }
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
        return cipher.doFinal(encrypted);
    }

    /**
     * 转换摘要字节为客户端清单使用的十六进制格式。
     *
     * @param bytes 摘要字节
     * @return 小写十六进制字符串
     */
    private String hex(byte[] bytes) {
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            result[i * 2] = HEX_DIGITS[value >>> 4];
            result[i * 2 + 1] = HEX_DIGITS[value & 0xf];
        }
        return new String(result);
    }

    /**
     * 底包内的密文物理区段。
     *
     * @author tongruiyuan
     * @since 2026-11-18 17:43
     */
    private static class Section {
        private final String bundle;
        private final long offset;
        private final int length;

        /**
         * 记录 ZIP 内映射区段。
         *
         * @param bundle APK 内的物理文件名
         * @param offset 区段偏移
         * @param length 密文长度
         */
        private Section(String bundle, long offset, int length) {
            this.bundle = bundle;
            this.offset = offset;
            this.length = length;
        }
    }

    /**
     * 版本清单内的逻辑文件及校验信息。
     *
     * @author tongruiyuan
     * @since 2026-11-18 17:43
     */
    private static class Asset {
        private final Section section;
        private final String md5;
        private final int size;

        /**
         * 记录解密后的预期文件信息。
         *
         * @param section 对应密文区段
         * @param md5 明文 MD5
         * @param size 明文长度
         */
        private Asset(Section section, String md5, int size) {
            this.section = section;
            this.md5 = md5;
            this.size = size;
        }
    }

    /**
     * 底包与两份客户端资源清单的索引。
     *
     * @author tongruiyuan
     * @since 2026-11-18 17:43
     */
    private static class Catalog {
        private final Path apk;
        private final byte[] assetManifest;
        private final byte[] dataManifest;
        private final Map<String, Asset> assets;
        private final Map<String, Asset> data;

        /**
         * 记录已经校验的底包及其资源。
         *
         * @param apk 固定底包位置
         * @param assetManifest 原始资源清单
         * @param dataManifest 原始数据清单
         * @param assets 允许读取的资源文件
         * @param data 允许读取的数据文件
         */
        private Catalog(Path apk, byte[] assetManifest, byte[] dataManifest,
                        Map<String, Asset> assets, Map<String, Asset> data) {
            this.apk = apk;
            this.assetManifest = assetManifest;
            this.dataManifest = dataManifest;
            this.assets = assets;
            this.data = data;
        }
    }
}
