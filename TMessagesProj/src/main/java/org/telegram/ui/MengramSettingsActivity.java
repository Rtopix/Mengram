package org.telegram.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MengramProxyEngine;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.TypefaceSpan;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class MengramSettingsActivity extends BaseFragment implements MengramProxyEngine.ProxyListener {

    private RecyclerListView listView;
    private ListAdapter listAdapter;
    private AlertDialog progressDialog;

    private int mtprotoRow, mtprotoInfoRow;
    private int cooldownRow, cooldownInfoRow;
    private int quicRow, quicInfoRow;
    private int rowCount;

    @Override
    public boolean onFragmentCreate() {
        rowCount = 0;
        mtprotoRow = rowCount++;
        mtprotoInfoRow = rowCount++;
        cooldownRow = rowCount++;
        cooldownInfoRow = rowCount++;
        quicRow = rowCount++;
        quicInfoRow = rowCount++;
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle("Mengram Settings");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listAdapter = new ListAdapter(context);
        listView.setAdapter(listAdapter);

        listView.setOnItemClickListener((view, position, x, y) -> {
            if (position == mtprotoRow) {
                boolean isEnabled = MengramProxyEngine.isMTProtoEnabled();
                if (!isEnabled) {
                    showLoadingDialog();
                    MengramProxyEngine.getInstance().setListener(this);
                    MengramProxyEngine.toggleMTProto(true);
                } else {
                    MengramProxyEngine.toggleMTProto(false);
                    listAdapter.notifyItemChanged(mtprotoRow);
                }
            } else if (position == cooldownRow) {
                showCooldownDialog();
            } else if (position == quicRow) {
                Toast.makeText(context, "Функция в разработке", Toast.LENGTH_SHORT).show();
            }
        });

        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        ((FrameLayout) fragmentView).addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        return fragmentView;
    }

    private void showLoadingDialog() {
        progressDialog = new AlertDialog(getParentActivity(), 3);
        progressDialog.setMessage("Mengram ищет лучший прокси...");
        progressDialog.setCanCancel(false);
        progressDialog.setButton(DialogInterface.BUTTON_NEGATIVE, "Отмена", (dialog, which) -> {
            MengramProxyEngine.getInstance().cancelSearch();
            dialog.dismiss();
            listAdapter.notifyItemChanged(mtprotoRow);
        });
        showDialog(progressDialog);
    }

    private void showCooldownDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Время ожидания (кулдаун)");

        String[] options = {"10 секунд", "15 секунд (рекомендуется)", "30 секунд", "1 минута"};
        int[] values = {10, 15, 30, 60};

        builder.setItems(options, (dialog, which) -> {
            MengramProxyEngine.setRotationCooldown(values[which]);
            listAdapter.notifyItemChanged(cooldownRow);
        });
        showDialog(builder.create());
    }

    @Override
    public void onProxyFound(MengramProxyEngine.ProxyInfo proxy) {
        if (progressDialog != null) progressDialog.dismiss();
        Toast.makeText(getParentActivity(), "Прокси активирован: " + proxy.pingMs + "ms", Toast.LENGTH_SHORT).show();
        listAdapter.notifyItemChanged(mtprotoRow);
    }

    @Override
    public void onProxyError(String message) {
        if (progressDialog != null) progressDialog.dismiss();
        Toast.makeText(getParentActivity(), "Ошибка: " + message, Toast.LENGTH_LONG).show();
        listAdapter.notifyItemChanged(mtprotoRow);
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        MengramProxyEngine.getInstance().setListener(null);
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private Context mContext;
        public ListAdapter(Context context) { mContext = context; }

        @Override
        public int getItemCount() { return rowCount; }

        @Override
        public int getItemViewType(int position) {
            if (position == mtprotoRow || position == cooldownRow || position == quicRow) return 1;
            return 2;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            if (viewType == 1) {
                view = new TextCheckCell(mContext);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else {
                view = new TextInfoPrivacyCell(mContext);
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            int type = holder.getItemViewType();

            if (type == 1) {
                TextCheckCell cell = (TextCheckCell) holder.itemView;
                if (position == mtprotoRow) {
                    SpannableStringBuilder title = new SpannableStringBuilder("Авто-прокси MTProto ");
                    addBadge(title, "BETA", 0xff2196f3);
                    cell.setTextAndCheck(title, MengramProxyEngine.isMTProtoEnabled(), true);
                    cell.setEnabled(true, null);
                    cell.getCheckBox().setVisibility(View.VISIBLE);
                } else if (position == cooldownRow) {
                    int seconds = MengramProxyEngine.getRotationCooldown();
                    cell.setTextAndCheck("Кулдаун ротации: " + seconds + "с", false, false);
                    cell.getCheckBox().setVisibility(View.GONE);
                    cell.setEnabled(true, null);
                } else if (position == quicRow) {
                    SpannableStringBuilder title = new SpannableStringBuilder("Обход по QUIC ");
                    addBadge(title, "SOON", 0xff9e9e9e);
                    cell.setTextAndCheck(title, false, false);
                    cell.getCheckBox().setVisibility(View.VISIBLE);
                    cell.setEnabled(false, null);
                }
            } else if (type == 2) {
                TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                if (position == mtprotoInfoRow) {
                    cell.setText("Автоматический подбор быстрых FakeTLS прокси для стабильной связи.");
                    cell.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                } else if (position == cooldownInfoRow) {
                    cell.setText("Если соединение не установится в течение этого времени, Mengram автоматически переключит прокси.");
                    cell.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                } else if (position == quicInfoRow) {
                    cell.setText("Использование UDP для маскировки трафика (в разработке).");
                    cell.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
                }
            }
        }

        private void addBadge(SpannableStringBuilder builder, String text, int color) {
            int start = builder.length();
            builder.append(text);
            builder.setSpan(new TypefaceSpan(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), AndroidUtilities.dp(11), color), start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int pos = holder.getAdapterPosition();
            return pos == mtprotoRow || pos == cooldownRow;
        }
    }
}
