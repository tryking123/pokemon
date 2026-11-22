package com.qk.local;

import android.app.Activity;
import android.app.AlertDialog;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 当前宠物的本地定制表单，合法选项由业务目录提供。
 *
 * @author tongruiyuan
 * @since 2026-11-22 03:53
 */
public final class PetCustomizeActivity extends Activity {
    private LocalApi.Session session;
    private String roleId;
    private String petId;
    private AlertDialog dialog;
    private EditText level;
    private Spinner aptitude;
    private Spinner talent;
    private Spinner talent2;
    private final List<Spinner> aureoles = new ArrayList<>();
    private boolean saving;
    private int minLevel;
    private int maxLevel;
    private String receipt = UUID.randomUUID().toString();

    /** 下拉选项的原始配置编号及说明。 */
    private static final class Choice {
        final int id;
        final String label;

        /**
         * 创建合法配置选项。
         * @param id 原配置编号
         * @param label 中文显示说明
         */
        Choice(int id, String label) {
            this.id = id;
            this.label = label;
        }

        /** @return 用户可读的配置名称 */
        @Override public String toString() { return label; }
    }

    /**
     * 校验深链绑定的角色及宠物，读取当前状态和目录。
     * @param state 恢复的表单状态
     */
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Uri uri = getIntent().getData();
        session = LocalApi.currentSession();
        if (session == null || uri == null || !"pokemon-local".equals(uri.getScheme())
                || !"pet-customize".equals(uri.getHost())) {
            fail("请从当前宠物详情打开定制");
            return;
        }
        roleId = uri.getQueryParameter("role");
        petId = uri.getQueryParameter("pet");
        if (roleId == null || petId == null || roleId.isEmpty() || petId.isEmpty()
                || roleId.length() > 100 || petId.length() > 128) {
            fail("宠物编号无效，请重新打开详情");
            return;
        }
        if (state != null) receipt = state.getString("receipt", receipt);
        LocalApi.run(this, () -> LocalApi.petCustomizeRead(session, roleId, petId), (result, error) -> {
            if (error != null) { fail(LocalApi.errorText(error)); return; }
            try { showForm(result, state); }
            catch (Exception failure) { fail("宠物配置读取失败：" + failure.getMessage()); }
        });
    }

    /**
     * 显示当前个体的可编辑字段，不改变星级或出战状态。
     * @param result 当前宠物与合法目录
     * @param saved 尚未保存的表单
     * @throws Exception 配置目录缺失或无法解析
     */
    private void showForm(JSONObject result, Bundle saved) throws Exception {
        JSONObject current = result.getJSONObject("current");
        JSONObject catalog = result.getJSONObject("catalog");
        minLevel = catalog.getJSONObject("level").getInt("min");
        maxLevel = catalog.getJSONObject("level").getInt("max");
        JSONObject draft = saved != null && saved.containsKey("draft")
                ? new JSONObject(saved.getString("draft")) : current;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        form.setPadding(pad, pad, pad, pad);
        text(form, "仅修改当前宠物；星级保持 " + current.optInt("star", 1) + " 星。");
        text(form, "等级（" + minLevel + "—" + maxLevel + "）");
        level = new EditText(this);
        level.setInputType(InputType.TYPE_CLASS_NUMBER);
        level.setSingleLine(true);
        level.setText(String.valueOf(draft.optInt("level", minLevel)));
        form.addView(level);
        aptitude = select(form, "资质", catalog.getJSONArray("aptitudes"), draft.optInt("aptitudes", 1), false);
        talent = select(form, "第一天赋", catalog.getJSONArray("talents"), draft.optInt("talent"), false);
        talent2 = select(form, "第二天赋", catalog.getJSONArray("talents"), draft.optInt("talent2", current.optInt("talent_2")), true);
        text(form, "光环最多预设4条，同一光环可重复选择。战斗时按宠物星级依次解锁，每个已解锁的光环分别生效。");
        JSONArray configured = draft.optJSONArray("aureoles");
        if (configured == null) configured = current.optJSONArray("aureole_id");
        if (configured == null) configured = new JSONArray();
        for (int index = 0; index < 4; index++) {
            aureoles.add(select(form, "光环预设 " + (index + 1), catalog.getJSONArray("aureoles"),
                    configured.optInt(index), true));
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(form);
        dialog = new AlertDialog.Builder(this).setTitle("宠物定制")
                .setView(scroll).setNegativeButton("取消", (d, w) -> finish())
                .setPositiveButton("保存", null).create();
        dialog.setOnCancelListener(d -> finish());
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> save()));
        dialog.show();
    }

    /**
     * 根据目录生成下拉框并恢复已选原编号。
     * @param form 当前表单
     * @param title 字段中文名称
     * @param rows 业务目录的合法选项
     * @param selected 当前配置编号
     * @param empty 是否允许不预设
     * @return 已绑定选项的下拉框
     * @throws Exception 配置行格式错误
     */
    private Spinner select(LinearLayout form, String title, JSONArray rows, int selected, boolean empty) throws Exception {
        text(form, title);
        List<Choice> choices = new ArrayList<>();
        if (empty) choices.add(new Choice(0, "不预设"));
        for (int index = 0; index < rows.length(); index++) {
            JSONObject row = rows.getJSONObject(index);
            String description = row.optString("description");
            choices.add(new Choice(row.getInt("id"), row.getString("name")
                    + (description.isEmpty() ? "" : " · " + description)));
        }
        if (choices.isEmpty()) throw new IllegalStateException("缺少" + title + "配置");
        Spinner control = new Spinner(this);
        ArrayAdapter<Choice> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, choices);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        control.setAdapter(adapter);
        for (int index = 0; index < choices.size(); index++) if (choices.get(index).id == selected) control.setSelection(index);
        form.addView(control);
        return control;
    }

    /**
     * 在表单中添加中文说明。
     * @param form 当前表单
     * @param value 显示文字
     */
    private void text(LinearLayout form, String value) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setPadding(0, 10, 0, 4);
        form.addView(label);
    }

    /**
     * 收集当前宠物的字段，最终合法性仍由业务层校验。
     * @return 绑定当前个体与幂等回执的定制请求
     * @throws Exception 输入格式或等级不合法
     */
    private JSONObject requestBody() throws Exception {
        int chosenLevel;
        try { chosenLevel = Integer.parseInt(level.getText().toString().trim()); }
        catch (NumberFormatException failure) { throw new IllegalArgumentException("请输入有效等级"); }
        if (chosenLevel < minLevel || chosenLevel > maxLevel) throw new IllegalArgumentException("等级须在" + minLevel + "—" + maxLevel + "之间");
        JSONArray selected = new JSONArray();
        for (Spinner spinner : aureoles) {
            Choice choice = (Choice) spinner.getSelectedItem();
            if (choice.id == 0) continue;
            selected.put(choice.id);
        }
        return new JSONObject().put("roleId", roleId).put("petId", petId).put("requestId", receipt)
                .put("level", chosenLevel).put("aptitudes", ((Choice) aptitude.getSelectedItem()).id)
                .put("talent", ((Choice) talent.getSelectedItem()).id).put("talent2", ((Choice) talent2.getSelectedItem()).id)
                .put("aureoles", selected);
    }

    /** 保存当前个体的定制结果，失败时保留表单供修正。 */
    private void save() {
        if (saving) return;
        final JSONObject body;
        try { body = requestBody(); }
        catch (Exception failure) { Toast.makeText(this, failure.getMessage(), Toast.LENGTH_LONG).show(); return; }
        saving = true;
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        LocalApi.run(this, () -> LocalApi.petCustomizeSave(session, body), (result, error) -> {
            saving = false;
            if (error != null) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                Toast.makeText(this, LocalApi.errorText(error), Toast.LENGTH_LONG).show();
                return;
            }
            Toast.makeText(this, "已保存当前宠物定制", Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    /**
     * 保留未保存字段及请求回执，旋转屏幕后仍绑定原个体。
     * @param state Android 保存的状态
     */
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("receipt", receipt);
        if (level != null) {
            try { state.putString("draft", requestBody().toString()); }
            catch (Exception ignored) { state.putString("rawLevel", level.getText().toString()); }
        }
    }

    /**
     * 展示无法打开表单的原因。
     * @param message 中文错误原因
     */
    private void fail(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finish();
    }
}
