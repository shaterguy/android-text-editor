import com.shaterguy.opentext.TextFileCodec;
import java.nio.charset.CharacterCodingException;
import java.util.Arrays;

public final class TestTextFileCodec {
    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("TextFileCodec contract failed");
    }
    public static void main(String[] args) throws Exception {
        String text = "{\r\n  \"한글\": \"값😀\"\r\n}\r\n";
        byte[] utf8 = TextFileCodec.encode(text, "UTF-8", false);
        TextFileCodec.Document decoded = TextFileCodec.decode(utf8, null);
        check(text.equals(decoded.text));
        check("UTF-8".equals(decoded.encoding));
        check("CRLF".equals(decoded.lineEnding));
        check("abcd".equals(TextFileCodec.decode("abcd".getBytes("UTF-8"), null).text));
        for (String charset : new String[]{"UTF-8", "UTF-16LE", "UTF-16BE"}) {
            byte[] bytes = TextFileCodec.encode(text, charset, true);
            TextFileCodec.Document roundTrip = TextFileCodec.decode(bytes, null);
            check(text.equals(roundTrip.text));
            check(roundTrip.bom);
            check(Arrays.equals(bytes, TextFileCodec.encode(roundTrip.text, roundTrip.encoding, roundTrip.bom)));
        }
        String legacy = "한글 가나다 테스트\n";
        byte[] eucKr = TextFileCodec.encode(legacy, "EUC-KR", false);
        check(legacy.equals(TextFileCodec.decode(eucKr, "EUC-KR").text));
        boolean malformedRejected = false;
        try { TextFileCodec.decode(new byte[]{(byte) 0xc3, (byte) 0x28}, null); }
        catch (CharacterCodingException expected) { malformedRejected = true; }
        check(malformedRejected);
        boolean binaryRejected = false;
        try { TextFileCodec.decode(new byte[]{0, 1, 2, 3, 4}, null); }
        catch (IllegalArgumentException expected) { binaryRejected = true; }
        check(binaryRejected);
        boolean unrepresentableRejected = false;
        try { TextFileCodec.encode("😀", "EUC-KR", false); }
        catch (CharacterCodingException expected) { unrepresentableRejected = true; }
        check(unrepresentableRejected);
        System.out.println("TextFileCodec: 8 scenarios passed");
    }
}
