package com.phraseremote.twophone;

import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputConnection;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class RemoteImeService extends InputMethodService {
    public static final int TCP_PORT = 45454;
    public static final int DISCOVERY_PORT = 45455;
    public static final String DISCOVER = "PHRASE_REMOTE_DISCOVER_V1";
    public static final String HERE = "PHRASE_REMOTE_HERE_V1";

    private volatile boolean running;
    private ServerSocket serverSocket;
    private DatagramSocket discoverySocket;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView statusView;

    @Override
    public void onCreate() {
        super.onCreate();
        startServers();
    }

    @Override
    public View onCreateInputView() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(dp(12), dp(7), dp(12), dp(7));
        bar.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setTextSize(15);
        title.setText("Remote Keyboard · 等待手機 B");
        bar.addView(title, new LinearLayout.LayoutParams(-1, -2));

        statusView = new TextView(this);
        statusView.setTextSize(12);
        statusView.setText("配對碼：" + getPairCode() + " · 保持此鍵盤為目前輸入法");
        bar.addView(statusView, new LinearLayout.LayoutParams(-1, -2));
        return bar;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private String getPairCode() {
        String code = getSharedPreferences("prefs", MODE_PRIVATE).getString("pair_code", null);
        if (code == null || code.length() != 6) {
            code = String.format("%06d", 100000 + (int) (Math.random() * 900000));
            getSharedPreferences("prefs", MODE_PRIVATE).edit().putString("pair_code", code).apply();
        }
        return code;
    }

    private void setStatus(String s) {
        main.post(() -> {
            if (statusView != null) statusView.setText(s);
        });
    }

    private void startServers() {
        if (running) return;
        running = true;
        new Thread(this::tcpLoop, "PhraseRemote-TCP").start();
        new Thread(this::discoveryLoop, "PhraseRemote-UDP").start();
    }

    private void tcpLoop() {
        try (ServerSocket ss = new ServerSocket(TCP_PORT)) {
            serverSocket = ss;
            while (running) {
                Socket socket = ss.accept();
                new Thread(() -> handleClient(socket), "PhraseRemote-Client").start();
            }
        } catch (Exception ignored) {
        }
    }

    private void handleClient(Socket socket) {
        try (Socket s = socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8))) {

            s.setSoTimeout(5000);
            String first = in.readLine();
            if (first == null || !first.startsWith("PAIR|")) {
                reply(out, "ERR|PAIR_REQUIRED");
                return;
            }
            String code = first.substring(5).trim();
            if (!getPairCode().equals(code)) {
                reply(out, "ERR|WRONG_CODE");
                return;
            }
            reply(out, "OK|PAIRED");

            String line = in.readLine();
            if (line == null) return;
            if (!line.startsWith("TEXT|")) {
                reply(out, "ERR|BAD_COMMAND");
                return;
            }

            String encoded = line.substring(5);
            String text = new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8);
            boolean ok = commitRemoteText(text);
            if (ok) {
                reply(out, "OK|INSERTED");
                setStatus("已接收文字 · 配對碼：" + getPairCode());
            } else {
                reply(out, "ERR|NO_FOCUSED_TEXT_FIELD");
                setStatus("未找到輸入框 · 請先點手機 A 的文字框");
            }
        } catch (Exception ignored) {
        }
    }

    private void reply(BufferedWriter out, String line) throws Exception {
        out.write(line);
        out.write("\n");
        out.flush();
    }

    private boolean commitRemoteText(String text) {
        final boolean[] result = {false};
        CountDownLatch latch = new CountDownLatch(1);
        main.post(() -> {
            try {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) result[0] = ic.commitText(text, 1);
            } catch (Exception ignored) {
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(1500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        return result[0];
    }

    private void discoveryLoop() {
        try {
            DatagramSocket ds = new DatagramSocket(DISCOVERY_PORT);
            discoverySocket = ds;
            ds.setBroadcast(true);
            byte[] buf = new byte[1024];
            while (running) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                ds.receive(packet);
                String msg = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                if (DISCOVER.equals(msg)) {
                    String device = Build.MANUFACTURER + " " + Build.MODEL;
                    String response = HERE + "|" + device.replace("|", " ") + "|" + TCP_PORT;
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    DatagramPacket rp = new DatagramPacket(bytes, bytes.length, packet.getAddress(), packet.getPort());
                    ds.send(rp);
                }
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        try { if (discoverySocket != null) discoverySocket.close(); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
