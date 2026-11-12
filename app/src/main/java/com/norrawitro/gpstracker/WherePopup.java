package com.norrawitro.gpstracker;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The "คุณอยู่ที่ไหน?" floating window, drawn over whatever app is on screen (rule 12).
 * Needs the "display over other apps" permission; without it only the notification is shown.
 */
final class WherePopup {
    interface Answer { void done(String name); }

    private final Context ctx;
    private View root;

    WherePopup(Context ctx) { this.ctx = ctx; }

    static boolean allowed(Context c) {
        return Settings.canDrawOverlays(c);
    }

    boolean showing() { return root != null; }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics()));
    }

    /** Shows the window. Returns false when the permission is missing or the system refused. */
    boolean show(String detail, Answer answer) {
        if (root != null) return true;
        if (!allowed(ctx)) return false;
        boolean dark = (ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int bg = dark ? Color.parseColor("#18211D") : Color.WHITE;
        int ink = dark ? Color.parseColor("#E4ECE7") : Color.parseColor("#15231D");
        int muted = dark ? Color.parseColor("#97A89F") : Color.parseColor("#5B6B63");
        int field = dark ? Color.parseColor("#101614") : Color.parseColor("#EEF2EF");
        int accent = dark ? Color.parseColor("#6CC79C") : Color.parseColor("#1F6B4F");
        int accentInk = dark ? Color.parseColor("#0D1A14") : Color.WHITE;

        FrameLayout outer = new FrameLayout(ctx);
        outer.setPadding(dp(16), dp(16), dp(16), dp(16));

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(16));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(bg);
        cardBg.setCornerRadius(dp(18));
        card.setBackground(cardBg);
        card.setElevation(dp(12));

        TextView title = new TextView(ctx);
        title.setText("คุณอยู่ที่ไหน?");
        title.setTextColor(ink);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title);

        TextView sub = new TextView(ctx);
        sub.setText(detail);
        sub.setTextColor(muted);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setPadding(0, dp(4), 0, dp(12));
        card.addView(sub);

        EditText input = new EditText(ctx);
        input.setHint("พิมพ์ชื่อสถานที่");
        input.setTextColor(ink);
        input.setHintTextColor(muted);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable fieldBg = new GradientDrawable();
        fieldBg.setColor(field);
        fieldBg.setCornerRadius(dp(10));
        fieldBg.setStroke(dp(1), accent);
        input.setBackground(fieldBg);
        card.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView note = new TextView(ctx);
        note.setText("กด \"ปิด\" โดยไม่พิมพ์ชื่อ จะบันทึกแค่วันเวลาและพิกัด");
        note.setTextColor(muted);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        note.setPadding(0, dp(8), 0, dp(12));
        card.addView(note);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        Button close = new Button(ctx);
        close.setText("ปิด");
        close.setAllCaps(false);
        close.setTextColor(ink);
        close.setBackgroundColor(Color.TRANSPARENT);
        Button save = new Button(ctx);
        save.setText("บันทึก");
        save.setAllCaps(false);
        save.setTextColor(accentInk);
        GradientDrawable saveBg = new GradientDrawable();
        saveBg.setColor(accent);
        saveBg.setCornerRadius(dp(10));
        save.setBackground(saveBg);
        save.setPadding(dp(20), 0, dp(20), 0);
        row.addView(close);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.setMarginStart(dp(8));
        row.addView(save, sp);
        card.addView(row);

        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        outer.addView(card, cp);

        save.setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) { input.setError("พิมพ์ชื่อสถานที่ก่อน"); return; }
            hide();
            answer.done(name);
        });
        close.setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            hide();
            answer.done(name); // empty = closed without a name (rule 14)
        });
        input.setOnEditorActionListener((v, actionId, e) -> { save.performClick(); return true; });

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // Not touch-modal: taps outside the box go to the app underneath. Before, the box took every
                // touch on the screen, so the phone looked frozen until the question was answered.
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.CENTER;
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE;
        try {
            ctx.getSystemService(WindowManager.class).addView(outer, lp);
            root = outer;
            input.requestFocus();
            return true;
        } catch (Exception e) {
            root = null;
            return false;
        }
    }

    void hide() {
        if (root == null) return;
        try { ctx.getSystemService(WindowManager.class).removeView(root); } catch (Exception ignored) {}
        root = null;
    }
}
