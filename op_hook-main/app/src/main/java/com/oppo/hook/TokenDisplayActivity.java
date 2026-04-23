package io.github.oppohook;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class TokenDisplayActivity extends Activity {

    public static final String EXTRA_TOKEN = "extra_token";
    public static final String EXTRA_SOURCE = "extra_source";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_token_display);

        String token = getIntent().getStringExtra(EXTRA_TOKEN);
        String source = getIntent().getStringExtra(EXTRA_SOURCE);

        TextView titleView = findViewById(R.id.tv_title);
        TextView sourceView = findViewById(R.id.tv_source);
        TextView tokenView = findViewById(R.id.tv_token);
        Button copyButton = findViewById(R.id.btn_copy);
        Button confirmButton = findViewById(R.id.btn_confirm);

        titleView.setText("捕获到 OPPO Token");
        sourceView.setText(source != null && !source.isEmpty() ? "来源：" + source : "来源：未知");
        tokenView.setText(token != null ? token : "");

        copyButton.setOnClickListener(v -> {
            copyToken(token != null ? token : "");
            finish();
        });

        confirmButton.setOnClickListener(v -> finish());

        View root = findViewById(R.id.dialog_root);
        root.setOnClickListener(v -> {
            // 阻止点击穿透，外层空白不关闭
        });
        setFinishOnTouchOutside(false);
    }

    private void copyToken(String token) {
        try {
            ClipboardManager clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboardManager != null) {
                ClipData clipData = ClipData.newPlainText("oppo_token", token);
                clipboardManager.setPrimaryClip(clipData);
                Toast.makeText(this, "Token 已复制", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            Toast.makeText(this, "复制失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
