package org.telegram.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MengramProxyEngine;
import org.telegram.messenger.MengramProxyService;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.ZalgoFilter;
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
import org.telegram.ui.Components.ScrollSlidingTextTabStrip;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class MengramSettingsActivity extends BaseFragment {

    private RecyclerListView listView;
    private ListAdapter listAdapter;
    private ScrollSlidingTextTabStrip tabs;

    private int currentTab = 0;
    private final static int TAB_PROXY = 0;
    private final static int TAB_FILTERS = 1;

    private int wssProxyRow;
    private int wssProxyInfoRow;
    private int wssAutoRestartRow;
    private int wssTimeoutRow;
    private int wssAutoRestartInfoRow;
    private int autoProxyRow;
    private int autoProxyInfoRow;
    private int zalgoFilterRow;
    private int zalgoFilterInfoRow;
    private int rowCount;

    @Override
    public boolean onFragmentCreate() {
        updateRows();
        ZalgoFilter.loadSettings();
        return super.onFragmentCreate();
    }

    private void updateRows() {
        rowCount = 0;
        wssProxyRow = -1;
        wssProxyInfoRow = -1;
        wssAutoRestartRow = -1;
        wssTimeoutRow = -1;
        wssAutoRestartInfoRow = -1;
        autoProxyRow = -1;
        autoProxyInfoRow = -1;
        zalgoFilterRow = -1;
        zalgoFilterInfoRow = -1;

        if (currentTab == TAB_PROXY) {
            wssProxyRow = rowCount++;
            wssProxyInfoRow = rowCount++;
            wssAutoRestartRow = rowCount++;
            wssTimeoutRow = rowCount++;
            wssAutoRestartInfoRow = rowCount++;
            autoProxyRow = rowCount++;
            autoProxyInfoRow = rowCount++;
        } else if (currentTab == TAB_FILTERS) {
            zalgoFilterRow = rowCount++;
            zalgoFilterInfoRow = rowCount++;
        }
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

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        fragmentView = container;

        tabs = new ScrollSlidingTextTabStrip(context, getResourceProvider());
        tabs.addTextTab(TAB_PROXY, "Прокси");
        tabs.addTextTab(TAB_FILTERS, "Фильтры");
        tabs.setDelegate(new ScrollSlidingTextTabStrip.ScrollSlidingTabStripDelegate() {
            @Override
            public void onPageSelected(int id, boolean forward) {
                currentTab = id;
                updateRows();
                listAdapter.notifyDataSetChanged();
            }

            @Override
            public void onPageScrolled(float progress) {
            }
        });
        container.addView(tabs, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44));

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
            } else if (position == wssAutoRestartRow) {
                boolean enabled = !MengramProxyService.isAutoRestartEnabled();
                MengramProxyService.setAutoRestartEnabled(enabled);
                listAdapter.notifyItemChanged(wssAutoRestartRow);
            } else if (position == wssTimeoutRow) {
                AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                builder.setTitle("Таймаут переподключения (сек)");

                final android.widget.EditText input = new android.widget.EditText(getParentActivity());
                input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                input.setText(String.valueOf(MengramProxyService.getWatchdogTimeout()));
                input.setSelection(input.getText().length());
                FrameLayout inputContainer = new FrameLayout(getParentActivity());
                int pad = AndroidUtilities.dp(24);
                inputContainer.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                inputContainer.setPadding(pad, 0, pad, 0);
                builder.setView(inputContainer);

                builder.setPositiveButton("OK", (dialog, which) -> {
                    try {
                        int val = Integer.parseInt(input.getText().toString().trim());
                        if (val < 1) val = 1;
                        if (val > 60) val = 60;
                        MengramProxyService.setWatchdogTimeout(val);
                        listAdapter.notifyItemChanged(wssTimeoutRow);
                        listAdapter.notifyItemChanged(wssAutoRestartInfoRow);
                    } catch (NumberFormatException ignored) {}
                });
                builder.setNegativeButton("Отмена", null);
                showDialog(builder.create());
            } else if (position == zalgoFilterRow) {
                boolean enabled = !ZalgoFilter.isEnabled();
                ZalgoFilter.setEnabled(enabled);
                listAdapter.notifyItemChanged(zalgoFilterRow);
            }
        });

        container.addView(listView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        container.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        return fragmentView;
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private Context mContext;
        public ListAdapter(Context context) { mContext = context; }

        @Override
        public int getItemCount() { return rowCount; }

        @Override
        public int getItemViewType(int position) {
            if (position == wssProxyRow || position == wssAutoRestartRow || position == autoProxyRow || position == zalgoFilterRow) return 0;
            if (position == wssTimeoutRow) return 1;
            if (position == wssProxyInfoRow || position == wssAutoRestartInfoRow || position == autoProxyInfoRow || position == zalgoFilterInfoRow) return 2;
            return 1;
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
                } else if (position == wssAutoRestartRow) {
                    cell.setTextAndCheck("Авто-перезапуск WSS", MengramProxyService.isAutoRestartEnabled(), true);
                } else if (position == autoProxyRow) {
                    cell.setTextAndCheck("Авто-подбор прокси", MengramProxyEngine.isMTProtoEnabled(), true);
                } else if (position == zalgoFilterRow) {
                    cell.setTextAndCheck("Zalgo фильтр", ZalgoFilter.isEnabled(), true);
                }
            } else if (type == 1) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                if (position == wssTimeoutRow) {
                    cell.setTextAndValue("Таймаут переподключения", MengramProxyService.getWatchdogTimeout() + " сек", true);
                }
            } else if (type == 2) {
                TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                if (position == wssProxyInfoRow) {
                    cell.setText("Использовать защищенный туннель через CloudFlare для обхода блокировок.");
                } else if (position == wssAutoRestartInfoRow) {
                    cell.setText("Автоматически перезапускает движок WebSocket прокси, если соединение зависает дольше указанного времени.");
                } else if (position == autoProxyInfoRow) {
                    cell.setText("Автоматический поиск и подключение рабочих MTProto прокси.");
                } else if (position == zalgoFilterInfoRow) {
                    cell.setText("Удаляет из сообщений символы, которые визуально растягивают текст вверх и вниз.");
                }
            }
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            return position == wssProxyRow || position == wssAutoRestartRow || position == wssTimeoutRow || position == autoProxyRow || position == zalgoFilterRow;
        }
    }
}
