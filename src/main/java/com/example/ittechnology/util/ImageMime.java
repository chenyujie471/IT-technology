package com.example.ittechnology.util;

import java.util.Base64;

/**
 * 图片 MIME 魔数检测工具。
 *
 * 上游（尤其 Anthropic /v1/messages）会校验图片 base64 的魔数与声明 media_type 是否一致，
 * 不一致时返回 400（如"声明 image/jpeg 但实际是 image/webp"）。
 * 因此所有图片上传路径都应先用本工具按内容检测真实格式，再决定发给上游的 MIME。
 */
public final class ImageMime {

    private ImageMime() {}

    /**
     * 根据 base64 内容魔数检测真实图片格式，返回标准 MIME。
     * 支持 png / jpeg / gif / webp / bmp；无法识别时返回 null。
     *
     * @param base64 纯 base64 或 data URL（自动剥离前缀）
     */
    public static String detect(String base64) {
        if (base64 == null || base64.isEmpty()) return null;
        String b64 = base64.trim();
        // 兼容 data URL：data:image/png;base64,xxxx
        int comma = b64.indexOf(',');
        if (comma > 0 && b64.regionMatches(true, 0, "data:", 0, 5)) {
            b64 = b64.substring(comma + 1);
        }
        if (b64.isEmpty()) return null;

        byte[] head;
        try {
            // 解码前 64 个字符（约 48 字节）足够覆盖所有魔数长度
            int len = Math.min(b64.length(), 64);
            byte[] decoded = Base64.getDecoder().decode(b64.substring(0, len));
            int n = Math.min(decoded.length, 12);
            head = new byte[n];
            System.arraycopy(decoded, 0, head, 0, n);
        } catch (Exception e) {
            return null;
        }

        // PNG:  89 50 4E 47
        if (head.length >= 4 && head[0] == (byte) 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') {
            return "image/png";
        }
        // JPEG: FF D8 FF
        if (head.length >= 3 && head[0] == (byte) 0xFF && head[1] == (byte) 0xD8 && head[2] == (byte) 0xFF) {
            return "image/jpeg";
        }
        // GIF:  47 49 46 38 (GIF8)
        if (head.length >= 4 && head[0] == 'G' && head[1] == 'I' && head[2] == 'F' && head[3] == '8') {
            return "image/gif";
        }
        // WebP: 52 49 46 46 ... 57 45 42 50 (RIFF....WEBP)
        if (head.length >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
            return "image/webp";
        }
        // BMP:  42 4D (BM)
        if (head.length >= 2 && head[0] == 'B' && head[1] == 'M') {
            return "image/bmp";
        }
        return null;
    }

    /**
     * 以内容检测为准纠正声明的 MIME：
     *  - 内容能识别 → 返回真实 MIME（覆盖声明值）；
     *  - 内容无法识别（如 PDF、非图片）→ 返回声明的合法 MIME，缺省 image/png。
     */
    public static String resolve(String declared, String base64) {
        String real = detect(base64);
        if (real != null) return real;
        return (declared != null && declared.contains("/")) ? declared : "image/png";
    }
}
