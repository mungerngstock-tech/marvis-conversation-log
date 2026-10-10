package com.phraseremote.twophone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.Gravity;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;

public class MainActivity extends Activity {
    private SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<Phrase> phrases = new ArrayList<>();

    private LinearLayout root;
    private GridLayout phraseGrid;
    private EditText searchBox;
    private EditText hostBox;
    private EditText codeBox;
    private TextView connectionStatus;
    private String currentCategory = "全部";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        loadPhrases();
        showMode();
    }

    private void showMode() {
        String mode = prefs.getString("mode", "choose");
        if ("receiver".equals(mode)) showReceiver();
        else if ("sender".equals(mode)) showSender();
        else showModeChooser();
    }

    private void prepareRoot() {
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(24));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);
    }

    private TextView heading(String text, float size) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(Color.rgb(25, 25, 25));
        t.setPadding(0, dp(4), 0, dp(8));
        return t;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setMinHeight(dp(52));
        return b;
    }

    private void showModeChooser() {
        prepareRoot();
        root.addView(heading("雙手機文字遙控器", 27));
        root.addView(heading("同一個 APK 安裝在兩部手機。手機 A 做接收端，手機 B 做文字遙控端。", 15));

        Button receiver = button("手機 A · 接收端 / Remote Keyboard");
        receiver.setOnClickListener(v -> {
            prefs.edit().putString("mode", "receiver").apply();
            showReceiver();
        });
        root.addView(receiver, lpTop(10));

        Button sender = button("手機 B · 文字遙控端 / Phrase Pad");
        sender.setOnClickListener(v -> {
            prefs.edit().putString("mode", "sender").apply();
            showSender();
        });
        root.addView(sender, lpTop(10));
    }

    private void showReceiver() {
        prepareRoot();
        root.addView(heading("手機 A · 接收端", 25));
        root.addView(heading("設定一次之後，手機 A 可以一直停留在 WhatsApp、ChatGPT、Messenger 等對話畫面。", 14));

        String code = ensurePairCode();
        TextView codeView = heading("配對碼：" + code, 28);
        codeView.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(codeView, lpTop(10));
        root.addView(heading("本機 IP：" + getLocalIps(), 14));

        Button enable = button("1 · 啟用 Remote Keyboard");
        enable.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        root.addView(enable, lpTop(12));

        Button choose = button("2 · 選擇 Remote Keyboard 作目前鍵盤");
        choose.setOnClickListener(v -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        });
        root.addView(choose, lpTop(8));

        root.addView(heading("之後：打開對話 App → 點一下文字輸入框 → 保持 Remote Keyboard。手機 B 傳來的中文或英文會直接插入該輸入框。", 14), lpTop(12));

        Button regen = button("重新產生 6 位配對碼");
        regen.setOnClickListener(v -> {
            String newCode = String.format("%06d", 100000 + (int) (Math.random() * 900000));
            prefs.edit().putString("pair_code", newCode).apply();
            showReceiver();
        });
        root.addView(regen, lpTop(12));

        Button switchMode = button("切換成手機 B 遙控端");
        switchMode.setOnClickListener(v -> {
            prefs.edit().putString("mode", "sender").apply();
            showSender();
        });
        root.addView(switchMode, lpTop(20));
    }

    private void showSender() {
        prepareRoot();
        root.addView(heading("手機 B · Phrase Pad", 25));

        connectionStatus = heading("🔴 未測試連線", 14);
        root.addView(connectionStatus);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        hostBox = new EditText(this);
        hostBox.setHint("手機 A IP，例如 192.168.1.23");
        hostBox.setSingleLine(true);
        hostBox.setText(prefs.getString("host", ""));
        row.addView(hostBox, new LinearLayout.LayoutParams(0, dp(52), 1));
        Button scan = button("搜尋");
        scan.setOnClickListener(v -> discoverReceiver());
        row.addView(scan, new LinearLayout.LayoutParams(dp(94), dp(52)));
        root.addView(row, lpTop(4));

        codeBox = new EditText(this);
        codeBox.setHint("手機 A 的 6 位配對碼");
        codeBox.setInputType(InputType.TYPE_CLASS_NUMBER);
        codeBox.setSingleLine(true);
        codeBox.setText(prefs.getString("remote_code", ""));
        root.addView(codeBox, lpTop(6));

        Button test = button("儲存並測試連線");
        test.setOnClickListener(v -> testConnection());
        root.addView(test, lpTop(6));

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        Button add = button("＋ 新增文字");
        add.setOnClickListener(v -> editPhrase(null));
        tools.addView(add, new LinearLayout.LayoutParams(0, dp(52), 1));
        Button switchMode = button("接收端");
        switchMode.setOnClickListener(v -> {
            prefs.edit().putString("mode", "receiver").apply();
            showReceiver();
        });
        tools.addView(switchMode, new LinearLayout.LayoutParams(dp(105), dp(52)));
        root.addView(tools, lpTop(12));

        searchBox = new EditText(this);
        searchBox.setHint("搜尋已儲存文字…");
        searchBox.setSingleLine(true);
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { rebuildPhraseGrid(); }
            @Override public void afterTextChanged(Editable e) {}
        });
        root.addView(searchBox, lpTop(12));

        LinearLayout cats = new LinearLayout(this);
        cats.setOrientation(LinearLayout.HORIZONTAL);
        for (String c : new String[]{"全部", "常用", "工作", "家庭", "其他"}) {
            Button cb = button(c);
            cb.setTextSize(12);
            cb.setOnClickListener(v -> {
                currentCategory = ((Button) v).getText().toString();
                rebuildPhraseGrid();
            });
            cats.addView(cb, new LinearLayout.LayoutParams(0, dp(46), 1));
        }
        root.addView(cats, lpTop(6));

        phraseGrid = new GridLayout(this);
        phraseGrid.setColumnCount(2);
        phraseGrid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        root.addView(phraseGrid, lpTop(6));
        rebuildPhraseGrid();

        root.addView(heading("按一下：立即傳到手機 A 目前輸入框。長按：修改、刪除或收藏。", 12), lpTop(10));
    }

    private void discoverReceiver() {
        connectionStatus.setText("🟠 正在搜尋手機 A…");
        new Thread(() -> {
            DatagramSocket socket = null;
            try {
                socket = new DatagramSocket();
                socket.setBroadcast(true);
                socket.setSoTimeout(1800);
                byte[] data = RemoteImeService.DISCOVER.getBytes(StandardCharsets.UTF_8);
                boolean sent = false;
                Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
                while (nis != null && nis.hasMoreElements()) {
                    NetworkInterface ni = nis.nextElement();
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                        InetAddress broadcast = ia.getBroadcast();
                        if (broadcast != null) {
                            socket.send(new DatagramPacket(data, data.length, broadcast, RemoteImeService.DISCOVERY_PORT));
                            sent = true;
                        }
                    }
                }
                if (!sent) socket.send(new DatagramPacket(data, data.length, InetAddress.getByName("255.255.255.255"), RemoteImeService.DISCOVERY_PORT));

                byte[] buf = new byte[1024];
                DatagramPacket reply = new DatagramPacket(buf, buf.length);
                socket.receive(reply);
                String msg = new String(reply.getData(), reply.getOffset(), reply.getLength(), StandardCharsets.UTF_8);
                if (msg.startsWith(RemoteImeService.HERE + "|")) {
                    String[] parts = msg.split("\\|", 4);
                    String ip = reply.getAddress().getHostAddress();
                    String name = parts.length > 1 ? parts[1] : "手機 A";
                    main.post(() -> {
                        hostBox.setText(ip);
                        connectionStatus.setText("🟡 已找到：" + name + " · " + ip + "，請輸入配對碼");
                    });
                }
            } catch (Exception e) {
                main.post(() -> connectionStatus.setText("🔴 找不到接收端。請確認兩機同一 Wi‑Fi/Hotspot，手機 A 已選 Remote Keyboard。"));
            } finally {
                if (socket != null) socket.close();
            }
        }, "PhraseRemote-Discover").start();
    }

    private void testConnection() {
        saveRemoteSettings();
        connectionStatus.setText("🟠 測試中…");
        new Thread(() -> {
            String r = sendCommand(null);
            main.post(() -> connectionStatus.setText(r != null && r.startsWith("OK|PAIRED") ? "🟢 已連接手機 A" : humanError(r)));
        }).start();
    }

    private void sendPhrase(Phrase phrase) {
        saveRemoteSettings();
        connectionStatus.setText("🟠 傳送中…");
        new Thread(() -> {
            String r = sendCommand(phrase.text);
            main.post(() -> {
                if (r != null && r.startsWith("OK|INSERTED")) connectionStatus.setText("🟢 已送出：「" + shortText(phrase.title) + "」");
                else {
                    connectionStatus.setText(humanError(r));
                    Toast.makeText(this, humanError(r), Toast.LENGTH_LONG).show();
                }
            });
        }, "PhraseRemote-Send").start();
    }

    private String sendCommand(String text) {
        String host = prefs.getString("host", "").trim();
        String code = prefs.getString("remote_code", "").trim();
        if (host.isEmpty()) return "ERR|NO_HOST";
        if (code.length() != 6) return "ERR|NO_CODE";

        try (Socket s = new Socket()) {
            s.connect(new java.net.InetSocketAddress(host, RemoteImeService.TCP_PORT), 2200);
            s.setSoTimeout(3500);
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out.write("PAIR|" + code + "\n");
            out.flush();
            String pairReply = in.readLine();
            if (text == null) return pairReply;
            if (pairReply == null || !pairReply.startsWith("OK|PAIRED")) return pairReply;
            String encoded = Base64.encodeToString(text.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            out.write("TEXT|" + encoded + "\n");
            out.flush();
            return in.readLine();
        } catch (Exception e) {
            return "ERR|CONNECT";
        }
    }

    private String humanError(String r) {
        if (r == null || r.contains("CONNECT")) return "🔴 無法連接手機 A";
        if (r.contains("WRONG_CODE")) return "🔴 配對碼錯誤";
        if (r.contains("NO_FOCUSED_TEXT_FIELD")) return "🔴 手機 A 未選中文字輸入框";
        if (r.contains("NO_HOST")) return "🔴 請先搜尋或輸入手機 A IP";
        if (r.contains("NO_CODE")) return "🔴 請輸入 6 位配對碼";
        return "🔴 傳送失敗";
    }

    private void saveRemoteSettings() {
        if (hostBox == null || codeBox == null) return;
        prefs.edit().putString("host", hostBox.getText().toString().trim()).putString("remote_code", codeBox.getText().toString().trim()).apply();
    }

    private void rebuildPhraseGrid() {
        if (phraseGrid == null) return;
        phraseGrid.removeAllViews();
        String q = searchBox == null ? "" : searchBox.getText().toString().trim().toLowerCase();
        List<Phrase> visible = new ArrayList<>();
        for (Phrase p : phrases) {
            boolean cat = "全部".equals(currentCategory) || currentCategory.equals(p.category);
            boolean text = q.isEmpty() || p.title.toLowerCase().contains(q) || p.text.toLowerCase().contains(q);
            if (cat && text) visible.add(p);
        }
        Collections.sort(visible, Comparator.comparing((Phrase p) -> !p.favorite).thenComparing(p -> p.title));
        for (Phrase p : visible) {
            Button b = button((p.favorite ? "★ " : "") + p.title);
            b.setGravity(Gravity.CENTER);
            b.setMaxLines(3);
            b.setOnClickListener(v -> sendPhrase(p));
            b.setOnLongClickListener(v -> { phraseOptions(p); return true; });
            GridLayout.LayoutParams gp = new GridLayout.LayoutParams();
            gp.width = 0;
            gp.height = dp(72);
            gp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            gp.setMargins(dp(3), dp(3), dp(3), dp(3));
            phraseGrid.addView(b, gp);
        }
    }

    private void phraseOptions(Phrase p) {
        String favText = p.favorite ? "取消收藏" : "收藏置頂";
        new AlertDialog.Builder(this).setTitle(p.title).setItems(new String[]{"修改", favText, "刪除"}, (d, which) -> {
            if (which == 0) editPhrase(p);
            else if (which == 1) {
                p.favorite = !p.favorite;
                savePhrases();
                rebuildPhraseGrid();
            } else {
                new AlertDialog.Builder(this).setMessage("刪除「" + p.title + "」？").setPositiveButton("刪除", (dd, w) -> {
                    phrases.remove(p);
                    savePhrases();
                    rebuildPhraseGrid();
                }).setNegativeButton("取消", null).show();
            }
        }).show();
    }

    private void editPhrase(Phrase existing) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), 0);
        EditText title = new EditText(this);
        title.setHint("按鈕名稱，例如：稍後回覆");
        title.setSingleLine(true);
        box.addView(title);
        EditText text = new EditText(this);
        text.setHint("真正要送出的完整文字");
        text.setMinLines(4);
        text.setGravity(Gravity.TOP);
        box.addView(text);
        Spinner category = new Spinner(this);
        String[] cats = new String[]{"常用", "工作", "家庭", "其他"};
        category.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, cats));
        box.addView(category);
        if (existing != null) {
            title.setText(existing.title);
            text.setText(existing.text);
            for (int i = 0; i < cats.length; i++) if (cats[i].equals(existing.category)) category.setSelection(i);
        }
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(existing == null ? "新增快捷文字" : "修改快捷文字").setView(box).setPositiveButton("儲存", null).setNegativeButton("取消", null).create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String t = title.getText().toString().trim();
            String body = text.getText().toString();
            if (t.isEmpty() || body.isEmpty()) {
                Toast.makeText(this, "請填寫按鈕名稱和文字", Toast.LENGTH_SHORT).show();
                return;
            }
            if (existing == null) phrases.add(new Phrase(t, body, category.getSelectedItem().toString(), false));
            else {
                existing.title = t;
                existing.text = body;
                existing.category = category.getSelectedItem().toString();
            }
            savePhrases();
            rebuildPhraseGrid();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void loadPhrases() {
        phrases.clear();
        String raw = prefs.getString("phrases_json", null);
        try {
            if (raw != null) {
                JSONArray a = new JSONArray(raw);
                for (int i = 0; i < a.length(); i++) phrases.add(Phrase.fromJson(a.getJSONObject(i)));
            }
        } catch (Exception ignored) {}
        if (phrases.isEmpty()) {
            phrases.add(new Phrase("你好嗎？", "你好嗎？", "常用", true));
            phrases.add(new Phrase("收到，謝謝", "收到，謝謝。", "常用", true));
            phrases.add(new Phrase("稍後回覆", "我稍後再回覆你。", "常用", false));
            phrases.add(new Phrase("請再說明", "請再詳細說明一下。", "工作", false));
            phrases.add(new Phrase("好的，沒問題", "好的，沒問題。", "常用", false));
            phrases.add(new Phrase("謝謝通知", "謝謝你的通知。", "工作", false));
            savePhrases();
        }
    }

    private void savePhrases() {
        try {
            JSONArray a = new JSONArray();
            for (Phrase p : phrases) a.put(p.toJson());
            prefs.edit().putString("phrases_json", a.toString()).apply();
        } catch (Exception ignored) {}
    }

    private String ensurePairCode() {
        String code = prefs.getString("pair_code", null);
        if (code == null || code.length() != 6) {
            code = String.format("%06d", 100000 + (int) (Math.random() * 900000));
            prefs.edit().putString("pair_code", code).apply();
        }
        return code;
    }

    private String getLocalIps() {
        ArrayList<String> ips = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress a = addresses.nextElement();
                    if (!a.isLoopbackAddress() && a.getHostAddress() != null && a.getHostAddress().indexOf(':') < 0) ips.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) {}
        return ips.isEmpty() ? "未連接 Wi‑Fi/Hotspot" : android.text.TextUtils.join(" / ", ips);
    }

    private String shortText(String s) { return s.length() <= 14 ? s : s.substring(0, 14) + "…"; }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
    private LinearLayout.LayoutParams lpTop(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(topDp);
        return p;
    }
}
