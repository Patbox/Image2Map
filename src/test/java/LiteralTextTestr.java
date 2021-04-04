import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class LiteralTextTestr {
    public static void main(String[] args) {
        String fileName = "wrejwr";
        Text display = new LiteralText("Poster for '" + fileName + "'").styled(style -> style.withItalic(false));
//        System.out.println(display.getRawString());
        System.out.println(display.asString());
        System.out.println(display.toString());
        System.out.println(display.getString());
        System.out.println(Text.Serializer.toJson(display));
    }
}
