package com.phraseremote.twophone;

import org.json.JSONException;
import org.json.JSONObject;

public class Phrase {
    public String title;
    public String text;
    public String category;
    public boolean favorite;

    public Phrase(String title, String text, String category, boolean favorite) {
        this.title = title;
        this.text = text;
        this.category = category;
        this.favorite = favorite;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("title", title);
        o.put("text", text);
        o.put("category", category);
        o.put("favorite", favorite);
        return o;
    }

    public static Phrase fromJson(JSONObject o) {
        return new Phrase(
                o.optString("title", "未命名"),
                o.optString("text", ""),
                o.optString("category", "常用"),
                o.optBoolean("favorite", false)
        );
    }
}
