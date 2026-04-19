package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MengramProxyEngine;
import org.telegram.messenger.MengramProxyService;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class MengramSettingsActivity extends BaseFragment implements MengramProxyEngine.ProxyListener {

    private RecyclerListView listView;
    private ListAdapter listAdapter;

    private int wssProxyRow;
    private int autoProxyRow;
    private int maskingRow;
    private int rotationRow;
    private int turboModeRow;
    private int autoBypassSectionRow;
    private int privateDNSRow;
    private int proxySourceRow;
    private int importProxyRow;
    private int networkSectionRow;
    private int statusInfoRow;
    private int rowCount;

    private String currentProxy = "None";
    private String currentPing = "0";
    private String currentStatus = "Idle";

    @Override
    public boolean onFragmentCreate() {
        rowCount = 0;
        wssProxyRow = rowCount++;
        autoProxyRow = rowCount++;
        maskingRow = rowCount++;
        rotationRow = rowCount++;
        turboModeRow = rowCount++;
        autoBypassSectionRow = rowCount++;
        privateDNSRow = rowCount++;
        proxySourceRow = rowCount++;
        importProxyRow = rowCount++;
        networkSectionRow = rowCount++;
        statusInfoRow = rowCount++;
        MengramProxyEngine.getInstance().setListener(this);
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
            if (position == wssProxyRow) {
                SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences("mengram_settings", 0);
                boolean wasEnabled = prefs.getBoolean("wss_proxy_enabled", false);
                boolean newState = !wasEnabled;

                prefs.edit().putBoolean("wss_proxy_enabled", newState).apply();

                Intent intent = new Intent(getParentActivity(), MengramProxyService.class);
                if (newState) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        getParentActivity().startForegroundService(intent);
                    } else {
                        getParentActivity().startService(intent);
                    }
                } else {
                    getParentActivity().stopService(intent);

                    try {
                        SharedPreferences mainPrefs = MessagesController.getGlobalMainSettings();
                        mainPrefs.edit()
                                .putBoolean("proxy_enabled", false)
                                .putBoolean("proxy_enabled_calls", false)
                                .putString("proxy_ip", "")
                                .putInt("proxy_port", 0)
                                .putString("proxy_user", "")
                                .putString("proxy_pass", "")
                                .putString("proxy_secret", "")
                                .commit();

                        NotificationCenter.getGlobalInstance()
                                .postNotificationName(NotificationCenter.proxySettingsChanged);
                    } catch (Exception e) {
                        FileLog.e(e);
                    }

                    ConnectionsManager.setProxySettings(false, "", 0, "", "", "");
                }

                listAdapter.notifyItemChanged(wssProxyRow);
            } else if (position == autoProxyRow) {
                boolean enabled = !MengramProxyEngine.isMTProtoEnabled();
                MengramProxyEngine.toggleMTProto(enabled);
                listAdapter.notifyItemChanged(autoProxyRow);
            } else if (position == maskingRow) {
                showMaskingDialog();
            } else if (position == rotationRow) {
                showRotationDialog();
            } else if (position == turboModeRow) {
                boolean enabled = !MengramProxyEngine.isTurboModeEnabled();
                MengramProxyEngine.setTurboMode(enabled);
                listAdapter.notifyItemChanged(turboModeRow);
            } else if (position == privateDNSRow) {
                boolean enabled = !MengramProxyEngine.isDoHEnabled();
                MengramProxyEngine.setDoHEnabled(enabled);
                listAdapter.notifyItemChanged(privateDNSRow);
            } else if (position == proxySourceRow) {
                showProxySourceDialog();
            } else if (position == importProxyRow) {
                openFilePicker();
            }
        });

        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        ((FrameLayout) fragmentView).addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        return fragmentView;
    }

    private void showMaskingDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Маскировка");
        String[] options = {"Google", "Microsoft", "Apple"};
        builder.setItems(options, (dialog, which) -> {
            MengramProxyEngine.setMasking(options[which]);
            listAdapter.notifyItemChanged(maskingRow);
        });
        showDialog(builder.create());
    }

    private void showRotationDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Кулдаун ротации");
        String[] options = {"10s", "15s", "30s", "60s"};
        int[] values = {10, 15, 30, 60};
        builder.setItems(options, (dialog, which) -> {
            MengramProxyEngine.setRotationCooldown(values[which]);
            listAdapter.notifyItemChanged(rotationRow);
        });
        showDialog(builder.create());
    }

    private void showProxySourceDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Источник прокси");
        String[] options = {"Standard", "Custom"};
        builder.setItems(options, (dialog, which) -> {
            if (which == 1) {
                showCustomSourceDialog();
            } else {
                MengramProxyEngine.setProxySource(0);
                listAdapter.notifyItemChanged(proxySourceRow);
            }
        });
        showDialog(builder.create());
    }

    private void showCustomSourceDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Custom Source URL");
        org.telegram.ui.Components.EditTextBoldCursor editText = new org.telegram.ui.Components.EditTextBoldCursor(getParentActivity());
        editText.setTextSize(1, 18);
        editText.setText(MengramProxyEngine.getCustomSourceUrl());
        editText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        editText.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(12), AndroidUtilities.dp(24), AndroidUtilities.dp(12));
        builder.setView(editText);
        builder.setPositiveButton("Save", (dialog, which) -> {
            MengramProxyEngine.setCustomSourceUrl(editText.getText().toString());
            MengramProxyEngine.setProxySource(1);
            listAdapter.notifyItemChanged(proxySourceRow);
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void openFilePicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("text/plain");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, 1);
        } catch (Exception ignored) {}
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode == 1 && resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try (InputStream inputStream = getParentActivity().getContentResolver().openInputStream(uri);
                     BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                    List<String> lines = new ArrayList<>();
                    String line;
                    while ((line = reader.readLine()) != null) lines.add(line);
                    MengramProxyEngine.getInstance().addProxiesFromList(lines);
                } catch (Exception e) {
                    FileLog.e(e);
                    Toast.makeText(getParentActivity(), "Failed to read the proxy list file", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(getParentActivity(), "Failed to read the proxy list file", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onProgress(int found, int total) {
        currentStatus = "Searching (" + found + "/" + total + ")";
        if (listAdapter != null) listAdapter.notifyItemChanged(statusInfoRow);
    }

    @Override
    public void onProxyFound(MengramProxyEngine.ProxyInfo proxy) {
        currentProxy = proxy.server;
        currentPing = String.valueOf(proxy.pingMs);
        currentStatus = "Stable";
        if (listAdapter != null) listAdapter.notifyItemChanged(statusInfoRow);
    }

    @Override
    public void onProxyError(String message) {
        currentStatus = "Error: " + message;
        if (listAdapter != null) listAdapter.notifyItemChanged(statusInfoRow);
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
            if (position == wssProxyRow || position == autoProxyRow || position == turboModeRow || position == privateDNSRow) return 0;
            if (position == maskingRow || position == rotationRow || position == proxySourceRow || position == importProxyRow) return 1;
            return 2;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            if (viewType == 0) {
                view = new TextCheckCell(mContext);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else if (viewType == 1) {
                view = new TextSettingsCell(mContext);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else {
                view = new TextInfoPrivacyCell(mContext);
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            int type = holder.getItemViewType();
            if (type == 0) {
                TextCheckCell cell = (TextCheckCell) holder.itemView;
                if (position == wssProxyRow) {
                    boolean isEnabled = ApplicationLoader.applicationContext
                            .getSharedPreferences("mengram_settings", 0)
                            .getBoolean("wss_proxy_enabled", false);
                    cell.setTextAndCheck("WSS-прокси (CloudFlare)", isEnabled, true);
                } else if (position == autoProxyRow) {
                    cell.setTextAndCheck("Авто-подбор прокси", MengramProxyEngine.isMTProtoEnabled(), true);
                } else if (position == turboModeRow) {
                    cell.setTextAndCheck("Турбо-режим (ротация)", MengramProxyEngine.isTurboModeEnabled(), false);
                } else if (position == privateDNSRow) {
                    cell.setTextAndCheck("Приватный DNS (DoH)", MengramProxyEngine.isDoHEnabled(), true);
                }
            } else if (type == 1) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                if (position == maskingRow) {
                    cell.setTextAndValue("Маскировка", MengramProxyEngine.getMasking(), true);
                } else if (position == rotationRow) {
                    cell.setTextAndValue("Кулдаун ротации", MengramProxyEngine.getRotationCooldown() + "s", true);
                } else if (position == proxySourceRow) {
                    String val = MengramProxyEngine.getProxySource() == 0 ? "Standard" : "Custom";
                    cell.setTextAndValue("Источник прокси", val, true);
                } else if (position == importProxyRow) {
                    cell.setText("Импорт списка (.txt)", false);
                }
            } else if (type == 2) {
                TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                if (position == autoBypassSectionRow) {
                    cell.setText("Настройка автоматического обхода через MTProto и FakeTLS.");
                    cell.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                } else if (position == networkSectionRow) {
                    cell.setText("Управление сетевыми ресурсами и внешними источниками.");
                    cell.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                } else if (position == statusInfoRow) {
                    cell.setText(String.format("Прокси: %s | Пинг: %sms | Статус: %s", currentProxy, currentPing, currentStatus));
                    cell.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
                }
            }
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int pos = holder.getAdapterPosition();
            return pos != autoBypassSectionRow && pos != networkSectionRow && pos != statusInfoRow;
        }
    }
}
