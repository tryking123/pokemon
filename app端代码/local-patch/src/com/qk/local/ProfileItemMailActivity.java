package com.qk.local;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 个人信息页的物品发放弹窗。沿用当前游戏会话，确认后投递到角色邮箱。
 * @author tongruiyuan
 * @since 2026-11-20 13:38
 */
public final class ProfileItemMailActivity extends Activity {
    private static final String ALL = "全部分类";
    private static final String ELF_GEAR = "精灵装备";
    private final List<Item> items = new ArrayList<>();
    private final List<Item> filtered = new ArrayList<>();
    private AlertDialog dialog;
    private ArrayAdapter<Item> results;
    private Spinner categories;
    private Spinner qualities;
    private ListView list;
    private EditText search;
    private EditText quantity;
    private TextView selection;
    private LocalApi.Session session;
    private String roleId;
    private Item selected;
    private boolean sending;
    private String previous = "";
    private String receipt;

    /** 一条可发放的随包目录项。 */
    private static final class Item {
        final String name, category, label;
        final int id, type, quality;

        /** 按资源目录读取物品的原编号、类别和显示名称。 */
        Item(JSONObject row) throws Exception {
            id = row.getInt("id");
            type = row.getInt("type");
            name = row.getString("name");
            category = row.getString("category");
            quality = row.optInt("quality");
            String details = ELF_GEAR.equals(category) ? row.optString("gearKind") + "  ·  " + quality + "阶"
                    + (row.optInt("gearLevel") > 0 ? "  ·  " + row.optInt("gearLevel") + "级" : "")
                    : "贴纸".equals(category) ? "贴纸  ·  刻印等级 " + row.optInt("stickerLevel") : category;
            label = name + "  ·  " + id + "  ·  " + details;
        }

        /** @return 列表的物品标签 */
        @Override public String toString() { return label; }
    }

    /** 验证原个人信息页传来的角色，加载本机可发放目录。
     * @param state Android 恢复的弹窗选项和回执
     */
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Uri uri = getIntent().getData();
        session = LocalApi.currentSession();
        if (session == null || uri == null || !"pokemon-local".equals(uri.getScheme())
                || !"item-mail".equals(uri.getHost()) || (roleId = uri.getQueryParameter("role")) == null
                || roleId.length() > 64 || roleId.isEmpty()) {
            Toast.makeText(this, "请从当前角色的个人信息页打开发物品", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        LocalApi.run(this, this::readCatalog, (rows, failure) -> {
            if (failure != null) {
                Toast.makeText(this, "物品目录加载失败", Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            items.addAll(rows);
            showItems(state);
        });
    }

    /** 从随包资源读取可在原邮箱领取的物品。 */
    private List<Item> readCatalog() throws Exception {
        List<Item> rows = new ArrayList<>();
        try (InputStream input = getAssets().open("pokemon-local/catalog/grant-mail-items.json");
             ByteArrayOutputStream data = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) != -1) data.write(buffer, 0, n);
            JSONArray source = new JSONObject(data.toString("UTF-8")).getJSONArray("items");
            for (int i = 0; i < source.length(); i++) {
                JSONObject row = source.optJSONObject(i);
                if (row != null && row.optBoolean("grantable")) rows.add(new Item(row));
            }
        }
        return rows;
    }

    /** 展示搜索、分类、单选列表及数量框。 */
    private void showItems(Bundle state) {
        android.content.Context theme = new android.view.ContextThemeWrapper(this, android.R.style.Theme_Material_Light);
        LinearLayout form = new LinearLayout(theme);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16), dp(4), dp(16), 0);
        form.setFocusableInTouchMode(true);
        search = new EditText(theme);
        search.setSingleLine(true);
        search.setHint("搜索物品名称或编号");
        form.addView(search);
        Set<String> groups = new LinkedHashSet<>();
        groups.add(ALL);
        for (Item item : items) groups.add(item.category);
        List<String> labels = new ArrayList<>(groups);
        categories = new Spinner(theme);
        ArrayAdapter<String> groupAdapter = new ArrayAdapter<>(theme, android.R.layout.simple_spinner_dropdown_item, labels);
        categories.setAdapter(groupAdapter);
        form.addView(categories);
        List<String> qualityLabels = new ArrayList<>();
        qualityLabels.add("全部等阶");
        java.util.SortedSet<Integer> availableQualities = new java.util.TreeSet<>();
        for (Item item : items) if (ELF_GEAR.equals(item.category)) availableQualities.add(item.quality);
        for (Integer value : availableQualities) qualityLabels.add(value + "阶");
        qualities = new Spinner(theme);
        qualities.setContentDescription("精灵装备等阶");
        qualities.setAdapter(new ArrayAdapter<>(theme, android.R.layout.simple_spinner_dropdown_item, qualityLabels));
        form.addView(qualities);
        list = new ListView(theme);
        results = new ArrayAdapter<>(theme, android.R.layout.simple_list_item_single_choice, filtered);
        list.setAdapter(results);
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        form.addView(list, new LinearLayout.LayoutParams(-1, Math.min(dp(255), getResources().getDisplayMetrics().heightPixels / 3)));
        selection = new TextView(theme);
        selection.setPadding(0, dp(5), 0, dp(3));
        selection.setTextColor(Color.rgb(36, 66, 91));
        form.addView(selection);
        LinearLayout amountRow = new LinearLayout(theme);
        amountRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView amountLabel = new TextView(theme);
        amountLabel.setText("发放数量");
        amountRow.addView(amountLabel);
        quantity = new EditText(theme);
        quantity.setSingleLine(true);
        quantity.setInputType(InputType.TYPE_CLASS_NUMBER);
        quantity.setSelectAllOnFocus(true);
        quantity.setText(state == null ? "1" : state.getString("quantity", "1"));
        amountRow.addView(quantity, new LinearLayout.LayoutParams(0, -2, 1));
        form.addView(amountRow);
        if (state != null) {
            previous = state.getString("previous", "");
            receipt = state.getString("receipt");
            search.setText(state.getString("search", ""));
            categories.setSelection(Math.max(0, labels.indexOf(state.getString("category", ALL))));
            qualities.setSelection(Math.max(0, qualityLabels.indexOf(state.getString("quality", "全部等阶"))));
            for (Item item : items) {
                if (item.id == state.getInt("id", -1) && item.type == state.getInt("type", -1)) selected = item;
            }
        }
        categories.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { refresh(); }
            public void onNothingSelected(AdapterView<?> parent) { refresh(); }
        });
        qualities.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { refresh(); }
            public void onNothingSelected(AdapterView<?> parent) { refresh(); }
        });
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { refresh(); }
            public void afterTextChanged(Editable text) { }
        });
        list.setOnItemClickListener((parent, view, position, id) -> {
            selected = filtered.get(position);
            updateSelection();
        });
        dialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("发物品到邮箱").setView(form)
                .setPositiveButton("发送邮件", null).setNegativeButton("取消", (d,w) -> finish()).create();
        dialog.setOnDismissListener(d -> finish());
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> send());
        refresh();
    }

    /** 仅显示符合分类及名称、编号搜索的物品。 */
    private void refresh() {
        if (results == null) return;
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        String group = String.valueOf(categories.getSelectedItem());
        boolean equipment = ELF_GEAR.equals(group);
        qualities.setVisibility(equipment ? View.VISIBLE : View.GONE);
        String quality = String.valueOf(qualities.getSelectedItem());
        filtered.clear();
        for (Item item : items) {
            if ((ALL.equals(group) || item.category.equals(group))
                    && (!equipment || qualities.getSelectedItemPosition() == 0 || quality.equals(item.quality + "阶"))
                    && (item.label.toLowerCase(Locale.ROOT).contains(query))) filtered.add(item);
        }
        if (!filtered.contains(selected)) selected = null;
        results.notifyDataSetChanged();
        list.clearChoices();
        if (selected != null) list.setItemChecked(filtered.indexOf(selected), true);
        updateSelection();
    }

    /** 显示选择结果，避免误发到其他物品。 */
    private void updateSelection() {
        selection.setText(selected != null ? "已选  " + selected.label : filtered.isEmpty() ? "没有匹配的物品" : "请选择要发放的物品");
        if (dialog != null && dialog.isShowing()) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(selected != null && !sending);
    }

    /** 确认邮件发放，同一份请求在失败重试时沿用回执。 */
    private void send() {
        if (selected == null || sending) return;
        long count;
        try { count = Long.parseLong(quantity.getText().toString().trim()); }
        catch (NumberFormatException invalid) { count = 0; }
        if (count < 1 || count > Integer.MAX_VALUE) {
            quantity.setError("请输入 1 至 2147483647 的整数");
            quantity.requestFocus();
            return;
        }
        Item item = selected;
        final long amount = count;
        String signature = item.type + "/" + item.id + "/" + amount;
        if (!signature.equals(previous) || receipt == null) {
            receipt = UUID.randomUUID().toString();
            previous = signature;
        }
        sending = true;
        dialog.setCancelable(false);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
        search.setEnabled(false); categories.setEnabled(false); list.setEnabled(false); quantity.setEnabled(false);
        updateSelection();
        String id = receipt;
        LocalApi.run(this, () -> LocalApi.sendItemMail(session, roleId, id, item.type, item.id, amount), (mail, failure) -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (failure == null) {
                Toast.makeText(this, "已寄到当前角色邮箱：" + item.name + " × " + amount, Toast.LENGTH_LONG).show();
                dialog.dismiss();
                return;
            }
            sending = false;
            dialog.setCancelable(true);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
            search.setEnabled(true); categories.setEnabled(true); list.setEnabled(true); quantity.setEnabled(true);
            updateSelection();
            selection.setText(LocalApi.errorText(failure));
        });
    }

    /** 保存选择和同一次寄信的回执。 */
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (quantity == null) return;
        state.putString("search", search.getText().toString());
        state.putString("quantity", quantity.getText().toString());
        state.putString("category", String.valueOf(categories.getSelectedItem()));
        state.putString("quality", String.valueOf(qualities.getSelectedItem()));
        state.putString("previous", previous);
        state.putString("receipt", receipt);
        if (selected != null) { state.putInt("id", selected.id); state.putInt("type", selected.type); }
    }

    /** @return 与设备密度相符的像素数 */
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
