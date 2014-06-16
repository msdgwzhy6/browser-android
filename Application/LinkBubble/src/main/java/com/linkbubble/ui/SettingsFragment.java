package com.linkbubble.ui;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebViewDatabase;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import com.linkbubble.BuildConfig;
import com.linkbubble.Config;
import com.linkbubble.Constant;
import com.linkbubble.DRM;
import com.linkbubble.MainApplication;
import com.linkbubble.MainController;
import com.linkbubble.R;
import com.linkbubble.Settings;
import com.linkbubble.util.ActionItem;
import com.linkbubble.util.Analytics;
import com.linkbubble.util.AppPickerList;
import com.linkbubble.util.Util;
import com.squareup.otto.Bus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;


/**
 * Created by gw on 11/09/13.
 */
public class SettingsFragment extends PreferenceFragment implements SharedPreferences.OnSharedPreferenceChangeListener {

    private Preference mInterceptLinksFromPreference;
    private Preference mWebViewTextZoomPreference;
    private ListPreference mUserAgentPreference;

    public static class IncognitoModeChangedEvent {
        public IncognitoModeChangedEvent(boolean value) {
            mIncognito = value;
        }
        public boolean mIncognito;
    }

    private void configureDefaultAppsList() {
        PreferenceCategory preferenceCategory = (PreferenceCategory)findPreference("preference_category_other_apps");
        preferenceCategory.removeAll();

        Preference noticePreference = new Preference(getActivity());

        PackageManager packageManager = getActivity().getPackageManager();
        TreeMap<String, ComponentName> defaultAppsMap = Settings.get().getDefaultAppsMap();
        if (defaultAppsMap != null && defaultAppsMap.size() > 0) {
            noticePreference.setSummary(R.string.preference_default_apps_notice_summary);
            preferenceCategory.addPreference(noticePreference);

            for (String key : defaultAppsMap.keySet()) {
                ComponentName componentName = defaultAppsMap.get(key);
                try {
                    ActivityInfo info = packageManager.getActivityInfo(componentName, 0);
                    final CharSequence label = info.loadLabel(packageManager);
                    final String host = key;
                    Preference preference = new Preference(getActivity());
                    preference.setTitle(label);
                    preference.setIcon(info.loadIcon(packageManager));
                    preference.setSummary(key);
                    preference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                        @SuppressLint("StringFormatMatches")        // Lint incorrectly flags this because 2 items are the same.
                        @Override
                        public boolean onPreferenceClick(Preference preference) {
                            Resources resources = getActivity().getResources();
                            AlertDialog alertDialog = new AlertDialog.Builder(getActivity()).create();
                            alertDialog.setIcon(R.drawable.ic_alert_icon);
                            alertDialog.setTitle(R.string.remove_default_title);
                            alertDialog.setMessage(String.format(resources.getString(R.string.remove_default_message), label, host, host));
                            alertDialog.setButton(AlertDialog.BUTTON_POSITIVE, resources.getString(R.string.action_remove),
                                    new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            Settings.get().removeDefaultApp(host);
                                            configureDefaultAppsList();
                                        }
                                    });
                            alertDialog.setButton(AlertDialog.BUTTON_NEGATIVE, resources.getString(R.string.action_cancel),
                                    new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                        }
                                    });
                            alertDialog.show();
                            return true;
                        }
                    });
                    preferenceCategory.addPreference(preference);
                } catch (PackageManager.NameNotFoundException e) {
                    e.printStackTrace();
                }
            }
        } else {
            noticePreference.setSummary(R.string.preference_default_apps_notice_no_defaults_summary);
            preferenceCategory.addPreference(noticePreference);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        MainApplication app = (MainApplication) getActivity().getApplicationContext();
        Bus bus = app.getBus();
        bus.register(this);

        // Load the preferences from an XML resource
        addPreferencesFromResource(R.xml.prefs);

        PreferenceCategory generalCategory = (PreferenceCategory) findPreference("preference_category_general");
        PreferenceCategory configurationCategory = (PreferenceCategory) findPreference("preference_category_configuration");
        PreferenceScreen helpScreen = (PreferenceScreen) getPreferenceScreen().findPreference("preference_screen_help");

        mInterceptLinksFromPreference = findPreference(Settings.PREFERENCE_IGNORE_LINKS_FROM);
        mInterceptLinksFromPreference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                getDontInterceptLinksFromDialog(getActivity()).show();
                return true;
            }
        });

        Preference incognitoButton = findPreference("preference_incognito");
        if (incognitoButton != null) {
            incognitoButton.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {

                    MainApplication app = (MainApplication) getActivity().getApplication();
                    Bus bus = app.getBus();
                    bus.post(new IncognitoModeChangedEvent((Boolean)newValue));

                    return true;
                }
            });
        }

        final CheckBoxPreference articleModePreference = (CheckBoxPreference)findPreference(Settings.KEY_ARTICLE_MODE_PREFERENCE);
        if (DRM.isLicensed()) {
            articleModePreference.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    if (MainController.get() != null && MainController.get().reloadAllTabs(getActivity())) {
                        Toast.makeText(getActivity(), R.string.article_mode_changed_reloading_current, Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }
            });
        } else {
            showProBanner(articleModePreference);
            articleModePreference.setChecked(false);
            articleModePreference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    articleModePreference.setChecked(false);
                    upsellPro(R.string.upgrade_article_mode);
                    return true;
                }
            });
        }

        final CheckBoxPreference okGooglePreference = (CheckBoxPreference)findPreference(Settings.KEY_OK_GOOGLE_PREFERENCE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            if (DRM.isLicensed()) {
                okGooglePreference.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                    @Override
                    public boolean onPreferenceChange(Preference preference, Object newValue) {
                        boolean checked = (Boolean) newValue;
                        MainApplication.postEvent(getActivity(), new ExpandedActivity.EnableHotwordSeviceEvent(checked));
                        return true;
                    }
                });
            } else {
                showProBanner(okGooglePreference);
                okGooglePreference.setChecked(false);
                okGooglePreference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(Preference preference) {
                        okGooglePreference.setChecked(false);
                        upsellPro(R.string.upgrade_ok_google);
                        return true;
                    }
                });
            }
        } else {
            okGooglePreference.setSummary(R.string.preference_ok_google_summary_jelly_bean);
            okGooglePreference.setEnabled(false);
        }

        Preference crashButton = findPreference("debug_crash");
        if (crashButton != null) {
            crashButton.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    throw new RuntimeException("CRASH BUTTON PRESSED!");                }
            });
        }

        final Preference leftConsumeBubblePreference = findPreference(Settings.PREFERENCE_LEFT_CONSUME_BUBBLE);
        leftConsumeBubblePreference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                AlertDialog alertDialog = ActionItem.getConfigureBubbleAlert(getActivity(), new ActionItem.OnActionItemSelectedListener() {
                    @Override
                    public void onSelected(ActionItem actionItem) {
                        Settings.get().setConsumeBubble(Constant.BubbleAction.ConsumeLeft, actionItem.mType, actionItem.getLabel(),
                                actionItem.mPackageName, actionItem.mActivityClassName);
                        leftConsumeBubblePreference.setSummary(Settings.get().getConsumeBubbleLabel(Constant.BubbleAction.ConsumeLeft));
                    }
                });
                alertDialog.show();
                return true;
            }
        });
        leftConsumeBubblePreference.setSummary(Settings.get().getConsumeBubbleLabel(Constant.BubbleAction.ConsumeLeft));

        final Preference rightConsumeBubblePreference = findPreference(Settings.PREFERENCE_RIGHT_CONSUME_BUBBLE);
        rightConsumeBubblePreference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                AlertDialog alertDialog = ActionItem.getConfigureBubbleAlert(getActivity(), new ActionItem.OnActionItemSelectedListener() {
                    @Override
                    public void onSelected(ActionItem actionItem) {
                        Settings.get().setConsumeBubble(Constant.BubbleAction.ConsumeRight, actionItem.mType, actionItem.getLabel(),
                                actionItem.mPackageName, actionItem.mActivityClassName);
                        rightConsumeBubblePreference.setSummary(Settings.get().getConsumeBubbleLabel(Constant.BubbleAction.ConsumeRight));
                    }
                });
                alertDialog.show();
                return true;
            }
        });
        rightConsumeBubblePreference.setSummary(Settings.get().getConsumeBubbleLabel(Constant.BubbleAction.ConsumeRight));

        Preference defaultBrowserPreference = findPreference(Settings.PREFERENCE_DEFAULT_BROWSER);
        defaultBrowserPreference.setSummary(Settings.get().getDefaultBrowserLabel());
        Drawable defaultBrowserIcon = Settings.get().getDefaultBrowserIcon(getActivity());
        if (defaultBrowserIcon != null) {
            defaultBrowserPreference.setIcon(defaultBrowserIcon);
        }
        defaultBrowserPreference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(final Preference preference) {
                AlertDialog alertDialog = ActionItem.getDefaultBrowserAlert(getActivity(), new ActionItem.OnActionItemSelectedListener() {
                    @Override
                    public void onSelected(ActionItem actionItem) {
                        Settings.get().setDefaultBrowser(actionItem.getLabel(), actionItem.mPackageName);
                        preference.setSummary(Settings.get().getDefaultBrowserLabel());
                        Drawable defaultBrowserIcon = Settings.get().getDefaultBrowserIcon(getActivity());
                        if (defaultBrowserIcon != null) {
                            preference.setIcon(defaultBrowserIcon);
                        }
                    }
                });
                alertDialog.show();
                return true;
            }
        });

        configureDefaultAppsList();

        findPreference("preference_clear_browser_cache").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {

                return onClearBrowserCachePreferenceClick();
            }
        });

        mWebViewTextZoomPreference = findPreference(Settings.PREFERENCE_WEBVIEW_TEXT_ZOOM);
        mWebViewTextZoomPreference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                getTextZoomDialog().show();
                return true;
            }
        });
        mWebViewTextZoomPreference.setSummary(Settings.get().getWebViewTextZoom() + "%");

        mUserAgentPreference = (ListPreference) findPreference(Settings.PREFERENCE_USER_AGENT);

        Preference sayThanksPreference = findPreference("preference_say_thanks");
        if (sayThanksPreference != null) {
            sayThanksPreference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    Intent intent = MainApplication.getStoreIntent(getActivity(), BuildConfig.STORE_FREE_URL);
                    if (intent != null) {
                        startActivity(intent);
                        Settings.get().setSayThanksClicked(true);
                        return true;
                    }
                    return false;
                }
            });
        }

        Preference getProPreference = findPreference("preference_get_pro");
        if (getProPreference != null) {
            getProPreference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    Intent intent = MainApplication.getStoreIntent(getActivity(), BuildConfig.STORE_PRO_URL);
                    if (intent != null) {
                        startActivity(intent);
                        return true;
                    }
                    return false;
                }
            });
        }

        Preference otherAppsPreference = findPreference("preference_my_other_apps");
        otherAppsPreference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                Intent intent = MainApplication.getStoreIntent(getActivity(), BuildConfig.STORE_MY_OTHER_APPS_URL);
                if (intent != null) {
                    startActivity(intent);
                    return true;
                }
                return false;
            }
        });

        if (DRM.isLicensed()) {
            generalCategory.removePreference(getProPreference);
            if (Settings.get().getSayThanksClicked()) {
                generalCategory.removePreference(sayThanksPreference);
                helpScreen.addPreference(sayThanksPreference);
            }
        } else {
            generalCategory.removePreference(sayThanksPreference);
        }

        findPreference("preference_credits").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {

            @Override
            public boolean onPreferenceClick(Preference preference) {
                getCreditDialog().show();
                return true;
            }
        });
        

        findPreference("preference_faq").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                FAQDialog dialog = new FAQDialog(getActivity());
                dialog.show();
                return true;
            }
        });

        findPreference("preference_show_welcome_message").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                MainApplication.openLink(getActivity(), Constant.WELCOME_MESSAGE_URL, Analytics.OPENED_URL_FROM_SETTINGS);
                return true;
            }
        });

        findPreference("preference_osl").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                WebView webView = new WebView(getActivity());
                webView.loadUrl("file:///android_asset/open_source_licenses.html");
                webView.setWebViewClient(new WebViewClient() {
                    public boolean shouldOverrideUrlLoading(WebView view, String url){
                        Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setData(Uri.parse(url));
                        startActivity(i);
                        return true;
                    }
                });

                AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
                builder.setIcon(R.drawable.ic_alert_icon);
                builder.setNegativeButton(R.string.action_ok, null);
                builder.setView(webView);
                builder.setTitle(R.string.preference_osl_title);

                AlertDialog alertDialog = builder.create();
                alertDialog.show();
                return true;
            }
        });

        findPreference("preference_privacy_policy").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                MainApplication.openLink(getActivity(), Constant.PRIVACY_POLICY_URL, Analytics.OPENED_URL_FROM_SETTINGS);
                return true;
            }
        });

        findPreference("preference_terms_of_service").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                MainApplication.openLink(getActivity(), Constant.TERMS_OF_SERVICE_URL, Analytics.OPENED_URL_FROM_SETTINGS);
                return true;
            }
        });

        Preference versionPreference = findPreference("preference_version");
        try {
            PackageInfo packageInfo = getActivity().getPackageManager().getPackageInfo(getActivity().getPackageName(), 0);
            versionPreference.setTitle(getString(R.string.preference_version_title) + " " + packageInfo.versionName);
        } catch (PackageManager.NameNotFoundException e) {
        }
        versionPreference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {

            @Override
            public boolean onPreferenceClick(Preference preference) {
                ChangeLogDialog changelogDialog = new ChangeLogDialog(getActivity());
                changelogDialog.show();
                //FAQDialog faqDialog = new FAQDialog(SettingsActivity.this);
                //faqDialog.show();
                return true;
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();

        getPreferenceScreen().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);

        if (mUserAgentPreference.getEntry() == null) {
            mUserAgentPreference.setValueIndex(0);
        }
        mUserAgentPreference.setSummary(mUserAgentPreference.getEntry());

        checkDefaultBrowser();
        configureDefaultAppsList();
    }

    @Override
    public void onPause() {
        super.onPause();

        getPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        
        MainApplication app = (MainApplication) getActivity().getApplicationContext();
        Bus bus = app.getBus();
        bus.unregister(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        Preference preference = findPreference(key);

        if (preference instanceof ListPreference) {
            ListPreference listPref = (ListPreference) preference;
            preference.setSummary(listPref.getEntry());
            if (preference == mUserAgentPreference) {
                if (MainController.get() != null && MainController.get().reloadAllTabs(getActivity())) {
                    Toast.makeText(getActivity(), R.string.user_agent_changed_reloading_current, Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    void showProBanner(Preference preference) {
        if (DRM.isLicensed() == false) {
            preference.setLayoutResource(R.layout.preference_pro_banner);
        }
    }

    long mLastUpsellTime;
    void upsellPro(int stringId) {
        if (System.currentTimeMillis() - mLastUpsellTime < 100) {
            return;
        }

        TextView textView = new TextView(getActivity());
        int padding = getResources().getDimensionPixelSize(R.dimen.upgrade_to_pro_dialog_padding);
        textView.setPadding(padding, padding, padding, padding);
        textView.setText(getString(stringId) + "\n\n" + getString(R.string.upgrade_from_settings_summary));

        ScrollView layout = new ScrollView(getActivity());
        layout.addView(textView);

        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setView(layout);
        builder.setIcon(0);
        builder.setPositiveButton(R.string.upgrade, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.STORE_PRO_URL));
                startActivity(intent);
            }
        });
        builder.setTitle(R.string.upgrade_to_pro);

        AlertDialog alertView = builder.create();
        alertView.show();
        mLastUpsellTime = System.currentTimeMillis();
    }

    void checkDefaultBrowser() {

        PackageManager packageManager = getActivity().getPackageManager();

        Preference setDefaultBrowserPreference = findPreference("preference_set_default_browser");
        // Will be null if onResume() is called after the preference has already been removed.
        if (setDefaultBrowserPreference != null) {
            setDefaultBrowserPreference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {

                @Override
                public boolean onPreferenceClick(Preference preference) {
                    // Via http://stackoverflow.com/a/13239706/328679
                    PackageManager packageManager = getActivity().getPackageManager();

                    ComponentName dummyComponentName = new ComponentName(getActivity().getApplication(),
                            DefaultBrowserResetActivity.class);
                    packageManager.setComponentEnabledSetting(dummyComponentName, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);

                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setData(Uri.parse(Config.SET_DEFAULT_BROWSER_URL));
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    getActivity().startActivity(intent);

                    packageManager.setComponentEnabledSetting(dummyComponentName, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP);
                    return true;
                }
            });

            ResolveInfo defaultBrowserResolveInfo = Util.getDefaultBrowser(packageManager);
            if (defaultBrowserResolveInfo != null) {
                if (defaultBrowserResolveInfo.activityInfo != null
                        && defaultBrowserResolveInfo.activityInfo.packageName.equals(BuildConfig.PACKAGE_NAME)) {
                    PreferenceCategory category = (PreferenceCategory) findPreference("preference_category_configuration");
                    category.removePreference(setDefaultBrowserPreference);
                }
            }
        }
    }

    void updateInterceptLinksFromPreference() {
        if (mInterceptLinksFromPreference != null) {
            //mInterceptLinksFromPreference.setSummary(Settings.get().getInterceptLinksFromAppName());
        }
    }

    AlertDialog getTextZoomDialog() {
        final View layout = View.inflate(getActivity(), R.layout.view_preference_text_zoom, null);

        final int initialZoom = Settings.get().getWebViewTextZoom();
        final TextView textView = (TextView) layout.findViewById(R.id.seekbar_title);
        final SeekBar seekBar = (SeekBar) layout.findViewById(R.id.seekbar_text_zoom);
        textView.setText((initialZoom + Settings.PREFERENCE_WEBVIEW_TEXT_ZOOM_MIN) + "%");
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (progress < 0) {
                    progress = 0;
                } else {
                    final int stepSize = 5;
                    progress = (Math.round(progress/stepSize))*stepSize;
                }
                seekBar.setProgress(progress);

                textView.setText((progress + Settings.PREFERENCE_WEBVIEW_TEXT_ZOOM_MIN) + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });
        seekBar.setMax(Settings.PREFERENCE_WEBVIEW_TEXT_ZOOM_MAX - Settings.PREFERENCE_WEBVIEW_TEXT_ZOOM_MIN);
        seekBar.setProgress(initialZoom - Settings.PREFERENCE_WEBVIEW_TEXT_ZOOM_MIN);

        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setIcon(R.drawable.ic_alert_icon);
        builder.setView(layout);
        builder.setTitle(R.string.preference_webview_text_zoom_title);

        AlertDialog alertDialog = builder.create();

        alertDialog.setButton(AlertDialog.BUTTON_POSITIVE, getString(android.R.string.ok), new DialogInterface.OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int which) {
                Settings.get().setWebViewTextZoom(seekBar.getProgress() + Settings.PREFERENCE_WEBVIEW_TEXT_ZOOM_MIN);
                int currentZoom = Settings.get().getWebViewTextZoom();
                mWebViewTextZoomPreference.setSummary(currentZoom + "%");
                if (currentZoom != initialZoom && MainController.get() != null) {
                    if (MainController.get().reloadAllTabs(getActivity())) {
                        Toast.makeText(getActivity(), R.string.preference_webview_text_zoom_reloading_current, Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });

        alertDialog.setButton(AlertDialog.BUTTON_NEUTRAL, getString(R.string.action_use_default), new DialogInterface.OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int which) {
                Settings.get().setWebViewTextZoom(Settings.PREFERENCE_WEBVIEW_TEXT_ZOOM_DEFAULT);
                int currentZoom = Settings.get().getWebViewTextZoom();
                mWebViewTextZoomPreference.setSummary(currentZoom + "%");
                if (currentZoom != initialZoom && MainController.get() != null) {
                    if (MainController.get().reloadAllTabs(getActivity())) {
                        Toast.makeText(getActivity(), R.string.preference_webview_text_zoom_reloading_current, Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });

        return alertDialog;
    }

    private static int TAPS_TO_FORCE_A_CRASH = 7;
    private int mForceCrashCountdown = TAPS_TO_FORCE_A_CRASH;
    Toast mForceCrashToast;

    void doCrash() {
        throw new RuntimeException("Forced Profile Image Exception");
    }

    AlertDialog getCreditDialog() {
        final View layout = View.inflate(getActivity(), R.layout.view_credits, null);

        ImageView profileImage = (ImageView)layout.findViewById(R.id.lacy_icon);
        profileImage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                if (mForceCrashCountdown > 0) {
                    mForceCrashCountdown--;
                    if (mForceCrashCountdown == 0) {
                        doCrash();
                    } else if (mForceCrashCountdown > 0
                            && mForceCrashCountdown < (TAPS_TO_FORCE_A_CRASH -2)) {
                        if (mForceCrashToast != null) {
                            mForceCrashToast.cancel();
                        }
                        mForceCrashToast = Toast.makeText(getActivity(),
                                "You are now " + mForceCrashCountdown + " step(s) away from FORCING A CRASH.",
                                Toast.LENGTH_SHORT);
                        mForceCrashToast.show();
                    }
                }
            }
        });

        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setIcon(R.drawable.ic_alert_icon);
        builder.setNegativeButton(R.string.action_ok, null);
        builder.setView(layout);
        builder.setTitle(R.string.credits_title);

        AlertDialog alertDialog = builder.create();
        return alertDialog;
    }

    private boolean onClearBrowserCachePreferenceClick() {

        final String clearCache = getString(R.string.preference_clear_cache);
        final String clearCookies = getString(R.string.preference_clear_cookies);
        final String clearFavicons = getString(R.string.preference_clear_favicons);
        final String clearFormData = getString(R.string.preference_clear_form_data);
        final String clearHistory = getString(R.string.preference_clear_history);
        final String clearPasswords = getString(R.string.preference_clear_passwords);

        final ArrayList<String> items = new ArrayList<String>();
        items.add(clearCache);
        items.add(clearCookies);
        items.add(clearFavicons);
        items.add(clearFormData);
        items.add(clearHistory);
        items.add(clearPasswords);

        ArrayAdapter<String> listAdapter = new ArrayAdapter<String>(getActivity(), android.R.layout.simple_list_item_multiple_choice, items);

        final ListView listView = new ListView(getActivity());
        listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        listView.setAdapter(listAdapter);
        for (int i = 0; i < items.size(); i++) {
            listView.setItemChecked(i, items.get(i).equals(clearFavicons) ? false : true);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setView(listView);
        builder.setIcon(R.drawable.ic_alert_icon);
        builder.setPositiveButton(R.string.action_clear_data, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {

                WebView webView = new WebView(getActivity());
                WebViewDatabase webViewDatabase = WebViewDatabase.getInstance(getActivity().getApplicationContext());
                boolean dataCleared = false;
                int count = listView.getCount();
                for (int i = 0; i < count; i++) {
                    if (listView.isItemChecked(i)) {
                        String item = items.get(i);
                        if (item.equals(clearCache)) {
                            webView.clearCache(true);
                            dataCleared = true;
                        } else if (item.equals(clearCookies)) {
                            CookieManager cookieManager = CookieManager.getInstance();
                            if (cookieManager != null && cookieManager.hasCookies()) {
                                cookieManager.removeAllCookie();
                            }
                            dataCleared = true;
                        } else if (item.equals(clearFavicons)) {
                            MainApplication.sDatabaseHelper.deleteAllFavicons();
                            MainApplication.recreateFaviconCache();
                            dataCleared = true;
                        } else if (item.equals(clearFormData)) {
                            if (webViewDatabase != null) {
                                webViewDatabase.clearFormData();
                                dataCleared = true;
                            }
                        } else if (item.equals(clearHistory)) {
                            webView.clearHistory();
                            MainApplication.sDatabaseHelper.deleteAllHistoryRecords();
                            Settings.get().saveCurrentTabs(null);
                            dataCleared = true;
                        } else if (item.equals(clearPasswords)) {
                            if (webViewDatabase != null) {
                                webViewDatabase.clearHttpAuthUsernamePassword();
                                webViewDatabase.clearUsernamePassword();
                                dataCleared = true;
                            }
                        }
                    }
                }

                if (dataCleared) {
                    boolean reloaded = false;

                    if (MainController.get() != null) {
                        reloaded = MainController.get().reloadAllTabs(getActivity());
                    }

                    Toast.makeText(getActivity(), reloaded ? R.string.private_data_cleared_reloading_current : R.string.private_data_cleared,
                            Toast.LENGTH_SHORT).show();
                }
            }
        });
        builder.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
            }
        });
        builder.setTitle(R.string.preference_clear_browser_cache_title);

        AlertDialog alertDialog = builder.create();
        alertDialog.show();

        return true;
    }

    static class AppInfo {
        String mActivityName;
        String mPackageName;
        String mDisplayName;
        String mSortName;

        AppInfo(String activityName, String packageName, String displayName) {
            mActivityName = activityName;
            mPackageName = packageName;
            mDisplayName = displayName;
            mSortName = displayName.toLowerCase(Locale.getDefault());
        }
    }

    public static class AppInfoComparator implements Comparator<AppInfo> {
        @Override
        public int compare(AppInfo lhs, AppInfo rhs) {
            return lhs.mSortName.compareTo(rhs.mSortName);
        }
    }

    public AlertDialog getDontInterceptLinksFromDialog(final Context context) {
        final List<String> browserPackageNames = Settings.get().getBrowserPackageNames();

        final View layout = AppPickerList.createView(context,
                ((MainApplication)context.getApplicationContext()).mIconCache,
                AppPickerList.SelectionType.MultipleSelection, new AppPickerList.Initializer() {
                    @Override
                    public boolean setChecked(String packageName, String activityName) {
                        return Settings.get().ignoreLinkFromPackageName(packageName) ? false : true;
                    }

                    @Override
                    public boolean addToList(String packageName) {
                        if (packageName.equals(BuildConfig.PACKAGE_NAME)) {
                            return false;
                        }

                        for (String browserPackageName : browserPackageNames) {
                            if (browserPackageName.equals(packageName)) {
                                return false;
                            }
                        }

                        return true;
                    }
                });

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setView(layout);
        builder.setIcon(R.drawable.ic_alert_icon);
        builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {

                ArrayList<String> ignorePackageNames = new ArrayList<String>();

                ArrayList<AppPickerList.AppInfo> results = AppPickerList.getUnselected(layout);
                if (results != null) {
                    for (AppPickerList.AppInfo result : results) {
                        ignorePackageNames.add(result.mPackageName);
                    }
                }

                Settings.get().setIgnoreLinksFromPackageNames(ignorePackageNames);
            }
        });
        builder.setTitle(R.string.preference_intercept_links_from_title);

        return builder.create();
    }
}
