package com.shaterguy.opentext;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.Arrays;

/** Strict text codec: malformed bytes and unrepresentable characters are never silently replaced. */
public final class TextFileCodec {
    public static final int MAX_FILE_BYTES = 8 * 1024 * 1024;
    public static final String[] ENCODINGS = {"UTF-8", "EUC-KR", "UTF-16LE", "UTF-16BE"};

    public static final class Document {
        public final String text;
        public final String encoding;
        public final boolean bom;
        public final String lineEnding;
        Document(String text, String encoding, boolean bom) {
            this.text = text;
            this.encoding = encoding;
            this.bom = bom;
            this.lineEnding = lineEnding(text);
        }
    }

    private TextFileCodec() {}

    public static Document decode(byte[] bytes, String selectedEncoding)
            throws CharacterCodingException {
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("파일 크기가 8 MiB 제한을 초과합니다.");
        }

        boolean utf8Bom = startsWith(bytes, new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf});
        boolean leBom = startsWith(bytes, new byte[]{(byte) 0xff, (byte) 0xfe});
        boolean beBom = startsWith(bytes, new byte[]{(byte) 0xfe, (byte) 0xff});

        String encoding;
        if (selectedEncoding != null) {
            if (!Arrays.asList(ENCODINGS).contains(selectedEncoding)) {
                throw new IllegalArgumentException("지원하지 않는 인코딩입니다.");
            }
            encoding = selectedEncoding;
        } else if (utf8Bom) {
            encoding = "UTF-8";
        } else if (leBom) {
            encoding = "UTF-16LE";
        } else if (beBom) {
            encoding = "UTF-16BE";
        } else {
            encoding = sniffUtf16(bytes);
            if (encoding == null) encoding = "UTF-8";
        }

        boolean hasBom = (encoding.equals("UTF-8") && utf8Bom)
                || (encoding.equals("UTF-16LE") && leBom)
                || (encoding.equals("UTF-16BE") && beBom);
        int offset = hasBom ? (encoding.equals("UTF-8") ? 3 : 2) : 0;

        if (!encoding.startsWith("UTF-16")) {
            for (int i = offset; i < bytes.length; i++) {
                if (bytes[i] == 0) {
                    throw new IllegalArgumentException("바이너리 파일은 편집할 수 없습니다.");
                }
            }
        }

        String text = Charset.forName(encoding).newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        return new Document(text, encoding, hasBom);
    }

    public static byte[] encode(String text, String encoding, boolean bom)
            throws CharacterCodingException {
        ByteBuffer encoded = Charset.forName(encoding).newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(text));
        byte[] data = new byte[encoded.remaining()];
        encoded.get(data);
        if (!bom) return data;

        byte[] prefix;
        if (encoding.equals("UTF-8")) {
            prefix = new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf};
        } else if (encoding.equals("UTF-16LE")) {
            prefix = new byte[]{(byte) 0xff, (byte) 0xfe};
        } else if (encoding.equals("UTF-16BE")) {
            prefix = new byte[]{(byte) 0xfe, (byte) 0xff};
        } else {
            return data;
        }
        ByteArrayOutputStream result = new ByteArrayOutputStream(data.length + prefix.length);
        result.write(prefix, 0, prefix.length);
        result.write(data, 0, data.length);
        return result.toByteArray();
    }

    public static String lineEnding(String text) {
        if (text.contains("\r\n")) return "CRLF";
        if (text.indexOf('\r') >= 0) return "CR";
        return "LF";
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) return false;
        }
        return true;
    }

    private static String sniffUtf16(byte[] bytes) {
        if (bytes.length < 4 || bytes.length % 2 != 0) return null;
        int pairs = Math.min(bytes.length / 2, 128);
        int evenNull = 0;
        int oddNull = 0;
        for (int i = 0; i < pairs; i++) {
            if (bytes[i * 2] == 0) evenNull++;
            if (bytes[i * 2 + 1] == 0) oddNull++;
        }
        if (oddNull >= Math.max(2, pairs / 3) && evenNull <= pairs / 10) return "UTF-16LE";
        if (evenNull >= Math.max(2, pairs / 3) && oddNull <= pairs / 10) return "UTF-16BE";
        return null;
    }
}
