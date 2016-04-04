/*
 * Copyright 2015 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.samples.apps.iosched.explore;

import android.app.LoaderManager;
import android.content.Intent;
import android.content.Loader;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.TextView;

import com.google.samples.apps.iosched.Config;
import com.google.samples.apps.iosched.R;
import com.google.samples.apps.iosched.model.TagMetadata;
import com.google.samples.apps.iosched.provider.ScheduleContract;
import com.google.samples.apps.iosched.settings.SettingsUtils;
import com.google.samples.apps.iosched.ui.BaseActivity;
import com.google.samples.apps.iosched.ui.SearchActivity;
import com.google.samples.apps.iosched.util.AnalyticsHelper;
import com.google.samples.apps.iosched.util.UIUtils;

import java.util.ArrayList;
import java.util.List;

import static com.google.samples.apps.iosched.util.LogUtils.LOGD;
import static com.google.samples.apps.iosched.util.LogUtils.LOGE;
import static com.google.samples.apps.iosched.util.LogUtils.makeLogTag;

/**
 * This activity displays all sessions based on the selected filters.
 * <p/>
 * It can either be invoked with specific filters or the user can choose the filters
 * to use from the alt_nav_bar.
 */
public class ExploreSessionsActivity extends BaseActivity
        implements Toolbar.OnMenuItemClickListener, LoaderManager.LoaderCallbacks<Cursor> {

    public static final String EXTRA_FILTER_TAG =
            "com.google.samples.apps.iosched.explore.EXTRA_FILTER_TAG";
    public static final String EXTRA_SHOW_LIVE_STREAM_SESSIONS =
            "com.google.samples.apps.iosched.explore.EXTRA_SHOW_LIVE_STREAM_SESSIONS";

    // The saved instance state filters
    private static final String STATE_FILTER_TAGS =
            "com.google.samples.apps.iosched.explore.STATE_FILTER_TAGS";
    private static final String STATE_CURRENT_URI =
            "com.google.samples.apps.iosched.explore.STATE_CURRENT_URI";

    private static final String SCREEN_LABEL = "ExploreSessions";

    private static final String TAG = makeLogTag(ExploreSessionsActivity.class);

    private static final int TAG_METADATA_TOKEN = 0x8;

    private static final int MODE_TIME_FIT = 1;
    private static final int MODE_EXPLORE = 2;

    private RecyclerView mFiltersList;
    private DrawerLayout mDrawerLayout;

    private TagMetadata mTagMetadata;
    private TagFilterHolder mTagFilterHolder;
    // Keep track of the current URI. This can diverge from Intent.getData() if the user
    // dismisses a particular timeslot. At that point, the Activity switches the mode
    // as well as the Uri used.
    private Uri mCurrentUri;
    private ExploreSessionsFragment mFragment;
    private int mMode;
    private View mTimeSlotLayout;
    private View mTimeSlotDivider;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.explore_sessions_act);

        mDrawerLayout = (DrawerLayout) findViewById(R.id.drawer_layout);
        mFiltersList = (RecyclerView) findViewById(R.id.filters);
        mTimeSlotLayout = findViewById(R.id.timeslot_view);
        mTimeSlotDivider = findViewById(R.id.timeslot_divider);
        TextView timeSlotTextView = (TextView) findViewById(R.id.timeslot);
        ImageButton dismissTimeSlotButton = (ImageButton) findViewById(R.id.close_timeslot);
        registerHideableHeaderView(findViewById(R.id.headerbar));

        mDrawerLayout.setDrawerShadow(R.drawable.drawer_shadow_flipped, GravityCompat.END);

        mFragment = (ExploreSessionsFragment) getFragmentManager()
                .findFragmentById(R.id.explore_sessions_frag);

        if (savedInstanceState != null) {

            mTagFilterHolder = savedInstanceState.getParcelable(STATE_FILTER_TAGS);
            mCurrentUri = savedInstanceState.getParcelable(STATE_CURRENT_URI);

        } else if (getIntent() != null) {
            mCurrentUri = getIntent().getData();
        }

        // Build the tag URI
        long[] interval = ScheduleContract.Sessions.getInterval(mCurrentUri);
        if (interval != null) {
            mMode = MODE_TIME_FIT;

            String title = getString(R.string.explore_sessions_time_slot_title,
                    getString(R.string.explore_sessions_show_day_n,
                            UIUtils.startTimeToDayIndex(interval[0])),
                    UIUtils.formatTime(interval[0], this));
            setTitle(title);

            mTimeSlotLayout.setVisibility(View.VISIBLE);
            mTimeSlotDivider.setVisibility(View.VISIBLE);
            timeSlotTextView.setText(title);
            dismissTimeSlotButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mTimeSlotLayout.setVisibility(View.GONE);
                    mTimeSlotDivider.setVisibility(View.GONE);
                    mMode = MODE_EXPLORE;
                    mCurrentUri = null;
                    reloadFragment();
                }
            });
        } else {
            mMode = MODE_EXPLORE;
        }

        // Add the back button to the toolbar.
        Toolbar toolbar = getActionBarToolbar();
        toolbar.setNavigationIcon(R.drawable.ic_up);
        toolbar.setNavigationContentDescription(R.string.close_and_go_back);
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                navigateUpOrBack(ExploreSessionsActivity.this, null);
            }
        });

        // Start loading the tag metadata. This will in turn call the fragment with the
        // correct arguments.
        getLoaderManager().initLoader(TAG_METADATA_TOKEN, null, this);

        // ANALYTICS SCREEN: View the Explore Sessions screen
        // Contains: Nothing (Page name is a constant)
        AnalyticsHelper.sendScreenView(SCREEN_LABEL);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);

        // Add the filter & search buttons to the toolbar.
        Toolbar toolbar = getActionBarToolbar();
        toolbar.inflateMenu(R.menu.explore_sessions_filtered);
        toolbar.setOnMenuItemClickListener(this);
        return true;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelable(STATE_FILTER_TAGS, mTagFilterHolder);
        outState.putParcelable(STATE_CURRENT_URI, mCurrentUri);
    }

    @Override
    public boolean onMenuItemClick(MenuItem menuItem) {
        switch (menuItem.getItemId()) {
            case R.id.menu_filter:
                mDrawerLayout.openDrawer(GravityCompat.END);
                return true;
            case R.id.menu_search:
                // ANALYTICS EVENT: Click the search button on the ExploreSessions screen
                // Contains: No data (Just that a search occurred, no search term)
                AnalyticsHelper.sendEvent(SCREEN_LABEL, "launchsearch", "");
                startActivity(new Intent(this, SearchActivity.class));
                return true;
        }
        return false;
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        // todo fix app bar hiding
        //enableActionBarAutoHide((CollectionView) findViewById(R.id.collection_view));
    }

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        if (id == TAG_METADATA_TOKEN) {
            return TagMetadata.createCursorLoader(this);
        }
        return null;
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor cursor) {
        switch (loader.getId()) {
            case TAG_METADATA_TOKEN:
                mTagMetadata = new TagMetadata(cursor);
                onTagMetadataLoaded();
                break;
            default:
                cursor.close();
        }
    }

    @Override
    public void onBackPressed() {
        if (mDrawerLayout != null && mDrawerLayout.isDrawerOpen(GravityCompat.END)) {
            mDrawerLayout.closeDrawer(GravityCompat.END);
        } else {
            super.onBackPressed();
        }
    }

    private void onTagMetadataLoaded() {
        if (mTagFilterHolder == null) {
            // Use the Intent Extras to set up the TagFilterHolder
            mTagFilterHolder = new TagFilterHolder();

            String tag = getIntent().getStringExtra(EXTRA_FILTER_TAG);
            TagMetadata.Tag userTag = mTagMetadata.getTag(tag);
            String userTagCategory = userTag == null ? null : userTag.getCategory();
            if (tag != null && userTagCategory != null) {
                mTagFilterHolder.add(tag, userTagCategory);
            }

            mTagFilterHolder.setShowLiveStreamedSessions(
                    getIntent().getBooleanExtra(EXTRA_SHOW_LIVE_STREAM_SESSIONS, false));

            // update the selected filters using the following logic:
            // a) For onsite attendees, we should default to showing all 'types'
            // (i.e. Sessions, code labs, sandbox, misc).
            if (SettingsUtils.isAttendeeAtVenue(this)) {
                List<TagMetadata.Tag> tags =
                        mTagMetadata.getTagsInCategory(Config.Tags.CATEGORY_TYPE);
                // Here we only add all 'types' if the user has not explicitly selected
                // one of the category_type tags.
                if (tags != null && !TextUtils.equals(userTagCategory, Config.Tags.CATEGORY_TYPE)) {
                    for (TagMetadata.Tag theTag : tags) {
                        mTagFilterHolder.add(theTag.getId(), theTag.getCategory());
                    }
                }
            } else {
                // b) For remote users, default to only showing Sessions that are Live streamed.
                TagMetadata.Tag theTag = mTagMetadata.getTag(Config.Tags.SESSIONS);
                if (!TextUtils.equals(theTag.getCategory(), userTagCategory)) {
                    mTagFilterHolder.add(theTag.getId(), theTag.getCategory());
                }
                mTagFilterHolder.setShowLiveStreamedSessions(true);
            }
        }
        reloadFragment();
        mFiltersList.setAdapter(new FilterAdapter(mTagMetadata));
    }

    /**
     * Set the activity title to be that of the selected tag name.
     * If the user chosen tag's category is present in the filter and there is a single tag
     * with that category then set the title to the specific tag name else
     * set the title to R.string.explore.
     */
    private void setActivityTitle() {
        if (mMode == MODE_EXPLORE && mTagMetadata != null) {
            String tag = getIntent().getStringExtra(EXTRA_FILTER_TAG);
            TagMetadata.Tag titleTag = tag == null ? null : mTagMetadata.getTag(tag);
            String title = null;
            if (titleTag != null &&
                    mTagFilterHolder.getCountByCategory(titleTag.getCategory()) == 1) {
                for (String tagId : mTagFilterHolder.getSelectedFilters()) {
                    TagMetadata.Tag theTag = mTagMetadata.getTag(tagId);
                    if (TextUtils.equals(titleTag.getCategory(), theTag.getCategory())) {
                        title = theTag.getName();
                    }
                }
            }
            setTitle(title == null ? getString(R.string.title_explore) : title);
        }
    }

    private void reloadFragment() {
        // Build the tag URI
        Uri uri = mCurrentUri;

        if (uri == null) {
            uri = ScheduleContract.Sessions.buildCategoryTagFilterUri(
                    ScheduleContract.Sessions.CONTENT_URI,
                    mTagFilterHolder.toStringArray(),
                    mTagFilterHolder.getCategoryCount());
        } else { // build a uri with the specific filters
            uri = ScheduleContract.Sessions.buildCategoryTagFilterUri(uri,
                    mTagFilterHolder.toStringArray(),
                    mTagFilterHolder.getCategoryCount());
        }
        setActivityTitle();
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        intent.putExtra(ExploreSessionsFragment.EXTRA_SHOW_LIVESTREAMED_SESSIONS,
                mTagFilterHolder.isShowLiveStreamedSessions());

        LOGD(TAG, "Reloading fragment with categories " + mTagFilterHolder.getCategoryCount() +
                " uri: " + uri +
                " showLiveStreamedEvents: " + mTagFilterHolder.isShowLiveStreamedSessions());

        mFragment.reloadFromArguments(intentToFragmentArguments(intent));
    }

    private void updateFilters(final TagMetadata.Tag filter, final boolean enable) {
        if (enable) {
            mTagFilterHolder.add(filter.getId(), filter.getCategory());
        } else {
            mTagFilterHolder.remove(filter.getId(), filter.getCategory());
        }
        reloadFragment();
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {

    }

    /**
     * Adapter for the list of filters that can be applied to this screen i.e. Theme, Session Type,
     * Live Streamed and Track.
     */
    private class FilterAdapter extends RecyclerView.Adapter {

        private static final int TYPE_FILTER = 0;

        private static final int TYPE_LIVE_STREAM_FILTER = 1;

        private static final int TYPE_DIVIDER = 2;

        private final List mItems;

        private final LayoutInflater mInflater;

        FilterAdapter(TagMetadata filters) {
            mInflater = LayoutInflater.from(ExploreSessionsActivity.this);
            mItems = new ArrayList();
            processFilters(filters);
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(final ViewGroup parent,
                                                          final int viewType) {
            switch (viewType) {
                case TYPE_FILTER:
                    return createFilterViewHolder(parent);
                case TYPE_LIVE_STREAM_FILTER:
                    return createLiveStreamFilterViewHolder(parent);
                case TYPE_DIVIDER:
                    return createDividerViewHolder(parent);
                default:
                    LOGE(TAG, "Unknown view type");
                    throw new IllegalArgumentException("Unknown view type");
            }
        }

        @Override
        public void onBindViewHolder(final RecyclerView.ViewHolder holder, final int position) {
            switch (getItemViewType(position)) {
                case TYPE_FILTER:
                    bindFilter((FilterViewHolder) holder, (TagMetadata.Tag) mItems.get(position));
                    break;
                case TYPE_LIVE_STREAM_FILTER:
                    bindLiveStreamFilter((FilterViewHolder) holder);
                    break;
            }
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }

        @Override
        public int getItemViewType(final int position) {
            Object item = mItems.get(position);
            if (item instanceof LiveStream) {
                return TYPE_LIVE_STREAM_FILTER;
            } else if (item instanceof Divider) {
                return TYPE_DIVIDER;
            }
            return TYPE_FILTER;
        }

        /**
         * We transform the provided data into a structure suitable for the RecyclerView
         * i.e. we build up {@link #mItems}, adding 'marker' items for dividers & live stream.
         */
        private void processFilters(TagMetadata tagMetadata) {
            List<TagMetadata.Tag> themes =
                    tagMetadata.getTagsInCategory(Config.Tags.CATEGORY_THEME);
            if (themes != null && !themes.isEmpty()) {
                for (TagMetadata.Tag theme : themes) {
                    mItems.add(theme);
                }
            }
            mItems.add(new Divider());

           List<TagMetadata.Tag> sessionTypes =
                   tagMetadata.getTagsInCategory(Config.Tags.CATEGORY_TYPE);
            if (sessionTypes != null && !sessionTypes.isEmpty()) {
                for (TagMetadata.Tag type : sessionTypes) {
                    mItems.add(type);
                }
            }
            mItems.add(new Divider());

            mItems.add(new LiveStream());
            mItems.add(new Divider());

            List<TagMetadata.Tag> topics =
                    tagMetadata.getTagsInCategory(Config.Tags.CATEGORY_TOPIC);
            if (topics != null && !topics.isEmpty()) {
                for (TagMetadata.Tag topic : topics) {
                    mItems.add(topic);
                }
            }
        }

        private FilterViewHolder createFilterViewHolder(final ViewGroup parent) {
            final FilterViewHolder holder = new FilterViewHolder(mInflater.inflate(
                    R.layout.explore_sessions_list_item_alt_drawer, parent, false));
            holder.checkbox.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(final View v) {
                    final TagMetadata.Tag filter =
                            (TagMetadata.Tag) mItems.get(holder.getAdapterPosition());
                    updateFilters(filter, holder.checkbox.isChecked());
                }
            });
            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(final View v) {
                    holder.checkbox.performClick();
                }
            });
            return holder;
        }

        private FilterViewHolder createLiveStreamFilterViewHolder(final ViewGroup parent) {
            final FilterViewHolder holder = new FilterViewHolder(mInflater.inflate(
                    R.layout.explore_sessions_list_item_livestream_alt_drawer, parent, false));
            holder.checkbox.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(final View v) {
                    mTagFilterHolder.setShowLiveStreamedSessions(holder.checkbox.isChecked());
                    reloadFragment();
                }
            });
            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(final View v) {
                    holder.checkbox.performClick();
                }
            });
            return holder;
        }

        private DividerViewHolder createDividerViewHolder(final ViewGroup parent) {
            // TODO we should use an ItemDecoration rather than a view
            return new DividerViewHolder(mInflater.inflate(
                    R.layout.explore_sessions_list_item_alt_header, parent, false));
        }

        private void bindFilter(final FilterViewHolder holder,
                                final TagMetadata.Tag filter) {
            holder.label.setText(filter.getName());
            holder.checkbox.setChecked(mTagFilterHolder.contains(filter.getId()));
        }

        private void bindLiveStreamFilter(final FilterViewHolder holder) {
            holder.checkbox.setChecked(mTagFilterHolder.isShowLiveStreamedSessions());
        }

    }

    private static class Divider { }

    private static class LiveStream { }

    private static class FilterViewHolder extends RecyclerView.ViewHolder {

        final TextView label;
        final CheckBox checkbox;

        public FilterViewHolder(final View itemView) {
            super(itemView);
            label = (TextView) itemView.findViewById(R.id.text_view);
            checkbox = (CheckBox) itemView.findViewById(R.id.filter_checkbox);
        }
    }

    private static class DividerViewHolder extends RecyclerView.ViewHolder {

        public DividerViewHolder(final View itemView) {
            super(itemView);
        }
    }

}