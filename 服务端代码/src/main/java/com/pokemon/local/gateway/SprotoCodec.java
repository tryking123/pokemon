package com.pokemon.local.gateway;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地游戏网关使用的 Sproto 数据编解码和稀疏压缩
 *
 * @author tongruiyuan
 * @since 2026-11-18 17:46
 */
final class SprotoCodec {

    private static final String INTEGER = "integer";
    private static final String BOOLEAN = "boolean";
    private static final String STRING = "string";

    private final Map<String, List<SprotoField>> types;

    /**
     * 使用网关支持的结构声明构造编解码器
     *
     * @param types 结构名称与字段表
     */
    SprotoCodec(Map<String, List<SprotoField>> types) {
        this.types = types;
    }

    /**
     * 按 Sproto 有序字段编码结构
     *
     * @param name 结构类型
     * @param value 字段值
     * @return 未压缩结构数据
     * @throws IOException 写入未声明字段或类型不支持
     */
    byte[] encode(String name, Map<String, Object> value) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int previousTag = -1;
        List<SprotoField> fields = fields(name);
        if (fields.stream().filter(field -> value.containsKey(field.name)).count() != value.size()) {
            throw new IOException("Sproto 结构存在未知字段：" + name);
        }
        for (SprotoField field : fields) {
            Object element = value.get(field.name);
            if (element == null) {
                continue;
            }
            int skipped = field.tag - previousTag - 1;
            if (skipped > 0) {
                putInt(header, (skipped - 1) * 2 + 1, 2);
            }
            previousTag = field.tag;
            if (!field.array && (INTEGER.equals(field.type) || BOOLEAN.equals(field.type))) {
                long number = toInteger(element);
                if (number >= 0 && number < 32767) {
                    putInt(header, (number + 1) * 2, 2);
                    continue;
                }
            }
            putInt(header, 0, 2);
            byte[] blob = field.array ? encodeList(field.type, (List<?>) element) : encodeValue(field.type, element);
            putInt(data, blob.length, 4);
            data.write(blob);
        }

        ByteArrayOutputStream result = new ByteArrayOutputStream();
        putInt(result, header.size() / 2, 2);
        header.writeTo(result);
        data.writeTo(result);
        return result.toByteArray();
    }

    /**
     * 解码一个 Sproto 结构并移动输入游标
     *
     * @param name 结构类型
     * @param source 带小端字节序的输入缓冲区
     * @return 已识别字段
     * @throws IOException 结构数据无效或被截断
     */
    Map<String, Object> decode(String name, ByteBuffer source) throws IOException {
        if (source.remaining() < 2) {
            throw new IOException("缺少 Sproto 字段数");
        }
        int count = Short.toUnsignedInt(source.getShort());
        if (count > source.remaining() / 2) {
            throw new IOException("Sproto 头被截断");
        }
        ByteBuffer header = source.slice().order(ByteOrder.LITTLE_ENDIAN);
        source.position(source.position() + count * 2);
        Map<String, Object> values = new LinkedHashMap<>();
        List<SprotoField> fields = fields(name);
        int fieldIndex = 0;
        int tag = -1;
        for (int index = 0; index < count; index++) {
            int word = Short.toUnsignedInt(header.getShort());
            tag++;
            if ((word & 1) != 0) {
                tag += word / 2;
                continue;
            }
            while (fieldIndex < fields.size() && fields.get(fieldIndex).tag < tag) {
                fieldIndex++;
            }
            SprotoField field = fieldIndex < fields.size() && fields.get(fieldIndex).tag == tag
                    ? fields.get(fieldIndex) : null;
            if (word == 0) {
                ByteBuffer blob = readBlob(source);
                if (field != null) {
                    values.put(field.name, field.array ? decodeList(field.type, blob) : decodeValue(field.type, blob));
                }
            } else if (field != null) {
                if (field.array || !(INTEGER.equals(field.type) || BOOLEAN.equals(field.type))) {
                    throw new IOException("Sproto 内联字段类型无效：" + field.name);
                }
                long number = word / 2 - 1;
                values.put(field.name, BOOLEAN.equals(field.type) ? number != 0 : number);
            }
        }
        return values;
    }

    /**
     * 压缩 Sproto 包，每组处理八个字节
     *
     * @param data 未压缩包
     * @return 已压缩包
     */
    static byte[] pack(byte[] data) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (int base = 0; base < data.length; base += 8) {
            int limit = Math.min(8, data.length - base);
            int mask = 0;
            for (int index = 0; index < limit; index++) {
                if (data[base + index] != 0) {
                    mask |= 1 << index;
                }
            }
            output.write(mask);
            if (mask == 255) {
                output.write(0);
                output.write(data, base, 8);
                continue;
            }
            for (int index = 0; index < limit; index++) {
                if ((mask & (1 << index)) != 0) {
                    output.write(data[base + index]);
                }
            }
        }
        return output.toByteArray();
    }

    /**
     * 解压原客户端包，支持原样连续块及位图块
     *
     * @param packed 已压缩包
     * @return 带小端顺序的原始数据缓冲区
     * @throws IOException 压缩数据被截断
     */
    static ByteBuffer unpack(byte[] packed) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int offset = 0; offset < packed.length; ) {
            int mask = Byte.toUnsignedInt(packed[offset++]);
            if (mask == 255) {
                if (offset == packed.length) {
                    throw new IOException("缺少 Sproto 原样块长度");
                }
                int length = (Byte.toUnsignedInt(packed[offset++]) + 1) * 8;
                if (length > packed.length - offset) {
                    throw new IOException("Sproto 原样块被截断");
                }
                out.write(packed, offset, length);
                offset += length;
                continue;
            }
            for (int index = 0; index < 8; index++) {
                if ((mask & (1 << index)) == 0) {
                    out.write(0);
                } else if (offset < packed.length) {
                    out.write(packed[offset++]);
                } else {
                    throw new IOException("Sproto 位图块被截断");
                }
            }
        }
        return ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * 编码一个数组的数据区
     *
     * @param type 元素类型
     * @param items 数组元素
     * @return 数组数据区
     * @throws IOException 元素编码失败
     */
    private byte[] encodeList(String type, List<?> items) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (items.isEmpty()) {
            return bytes.toByteArray();
        }
        if (INTEGER.equals(type)) {
            int width = items.stream().mapToLong(this::toInteger).anyMatch(number -> integerSize(number) == 8) ? 8 : 4;
            bytes.write(width);
            for (Object item : items) {
                putInt(bytes, toInteger(item), width);
            }
        } else if (BOOLEAN.equals(type)) {
            for (Object item : items) {
                bytes.write((int) toInteger(item));
            }
        } else {
            for (Object item : items) {
                byte[] value = encodeValue(type, item);
                putInt(bytes, value.length, 4);
                bytes.write(value);
            }
        }
        return bytes.toByteArray();
    }

    /**
     * 解码一个数组的数据区
     *
     * @param type 元素类型
     * @param source 限制在数组边界内的数据区
     * @return 有序数组元素
     * @throws IOException 元素数据格式无效
     */
    private List<Object> decodeList(String type, ByteBuffer source) throws IOException {
        List<Object> result = new ArrayList<>();
        if (!source.hasRemaining()) {
            return result;
        }
        if (INTEGER.equals(type)) {
            int width = Byte.toUnsignedInt(source.get());
            if ((width != 4 && width != 8) || source.remaining() % width != 0) {
                throw new IOException("Sproto 整数数组宽度无效");
            }
            while (source.hasRemaining()) {
                result.add(width == 4 ? source.getInt() : source.getLong());
            }
        } else if (BOOLEAN.equals(type)) {
            while (source.hasRemaining()) {
                result.add(source.get() != 0);
            }
        } else {
            while (source.hasRemaining()) {
                result.add(decodeValue(type, readBlob(source)));
            }
        }
        return result;
    }

    /**
     * 编码一个独立值的数据区
     *
     * @param type 值类型
     * @param value 值内容
     * @return 数据区
     * @throws IOException 嵌套结构编码失败
     */
    @SuppressWarnings("unchecked")
    private byte[] encodeValue(String type, Object value) throws IOException {
        if (STRING.equals(type)) {
            return ((String) value).getBytes(StandardCharsets.UTF_8);
        }
        if (INTEGER.equals(type) || BOOLEAN.equals(type)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            long number = toInteger(value);
            putInt(bytes, number, integerSize(number));
            return bytes.toByteArray();
        }
        return encode(type, (Map<String, Object>) value);
    }

    /**
     * 解码一个独立值的数据区
     *
     * @param type 值类型
     * @param source 限制在字段边界内的数据区
     * @return 字段值
     * @throws IOException 字段编码无效
     */
    private Object decodeValue(String type, ByteBuffer source) throws IOException {
        if (STRING.equals(type)) {
            return StandardCharsets.UTF_8.decode(source).toString();
        }
        if (INTEGER.equals(type) || BOOLEAN.equals(type)) {
            long number;
            if (source.remaining() == 4) {
                number = source.getInt();
            } else if (source.remaining() == 8) {
                number = source.getLong();
            } else {
                throw new IOException("Sproto 整数字段宽度无效");
            }
            return BOOLEAN.equals(type) ? number != 0 : number;
        }
        return decode(type, source);
    }

    /**
     * 查询结构字段
     *
     * @param name 结构名称
     * @return 有序字段
     * @throws IOException 结构未定义
     */
    private List<SprotoField> fields(String name) throws IOException {
        List<SprotoField> fields = types.get(name);
        if (fields == null) {
            throw new IOException("未定义 Sproto 结构：" + name);
        }
        return fields;
    }

    /**
     * 读取结构的数据区条目
     *
     * @param source 原始输入缓冲区
     * @return 单个条目切片
     * @throws IOException 长度无效
     */
    private ByteBuffer readBlob(ByteBuffer source) throws IOException {
        if (source.remaining() < 4) {
            throw new IOException("缺少 Sproto 数据区长度");
        }
        int length = source.getInt();
        if (length < 0 || length > source.remaining()) {
            throw new IOException("Sproto 数据区长度无效");
        }
        ByteBuffer result = source.slice().order(ByteOrder.LITTLE_ENDIAN);
        result.limit(length);
        source.position(source.position() + length);
        return result;
    }

    /**
     * 获取可直接编码为整数的值
     *
     * @param value 整数或布尔值
     * @return 数值表示
     */
    private long toInteger(Object value) {
        return value instanceof Boolean ? ((Boolean) value ? 1 : 0) : ((Number) value).longValue();
    }

    /**
     * 计算可容纳有符号整数的字节数
     *
     * @param number 整数值
     * @return 四字节或八字节
     */
    private int integerSize(long number) {
        return number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE ? 4 : 8;
    }

    /**
     * 以小端顺序写入固定长度整数
     *
     * @param out 目标输出流
     * @param value 数值
     * @param size 写入字节数
     */
    private void putInt(ByteArrayOutputStream out, long value, int size) {
        for (int offset = 0; offset < size; offset++) {
            out.write((int) (value >>> (offset * 8)) & 255);
        }
    }
}
