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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class MengramSettingsActivity extends BaseFragment {

    private RecyclerListView listView;
    private ListAdapter listAdapter;

    private int wssProxyRow;
    private int autoProxyRow;
    private int zalgoFilterRow;
    private int rowCount;

    @Override
    public boolean onFragmentCreate() {
        rowCount = 0;
        wssProxyRow = rowCount++;
        autoProxyRow = rowCount++;
        zalgoFilterRow = rowCount++;
        ZalgoFilter.loadSettings();
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
            } else if (position == zalgoFilterRow) {
                boolean enabled = !ZalgoFilter.isEnabled();
                ZalgoFilter.setEnabled(enabled);
                listAdapter.notifyItemChanged(zalgoFilterRow);
            }
        });

        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        ((FrameLayout) fragmentView).addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        return fragmentView;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private Context mContext;
        public ListAdapter(Context context) { mContext = context; }

        @Override
        public int getItemCount() { return rowCount; }

        @Override
        public int getItemViewType(int position) {
            if (position == wssProxyRow || position == autoProxyRow || position == zalgoFilterRow) return 0;
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
                } else if (position == zalgoFilterRow) {
                    cell.setTextAndCheck("Zalgo фильтр", ZalgoFilter.isEnabled(), true);
                }
            }
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }
    }
}
