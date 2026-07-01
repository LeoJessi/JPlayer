package top.jessi.videoplayer.player;

/**
 * Created by Jessi on 2026/7/1 17:19
 * Email：17324719944@189.cn
 * Describe：字幕
 */
public class TimedText {

    private final String text;

    public TimedText(String text) {
        this.text = (text == null ? "" : text);
    }

    public String getText() {
        return text;
    }
}

