/*
 * Copyright (C) 2013-2015 Dominik Schürmann <dominik@dominikschuermann.de>
 * Copyright (C) 2014-2015 Vincent Breitmoser <v.breitmoser@mugenguild.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.sufficientlysecure.keychain.ui;

import android.animation.ObjectAnimator;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MergeCursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.view.ActionMode;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.SearchView;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ViewAnimator;

import com.getbase.floatingactionbutton.FloatingActionButton;
import com.getbase.floatingactionbutton.FloatingActionsMenu;
import com.tonicartos.superslim.LayoutManager;

import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.R;
import org.sufficientlysecure.keychain.keyimport.ParcelableKeyRing;
import org.sufficientlysecure.keychain.operations.results.BenchmarkResult;
import org.sufficientlysecure.keychain.operations.results.ConsolidateResult;
import org.sufficientlysecure.keychain.operations.results.ImportKeyResult;
import org.sufficientlysecure.keychain.operations.results.OperationResult;
import org.sufficientlysecure.keychain.provider.KeychainContract;
import org.sufficientlysecure.keychain.provider.KeychainContract.KeyRings;
import org.sufficientlysecure.keychain.provider.KeychainDatabase;
import org.sufficientlysecure.keychain.provider.ProviderHelper;
import org.sufficientlysecure.keychain.service.BenchmarkInputParcel;
import org.sufficientlysecure.keychain.service.ConsolidateInputParcel;
import org.sufficientlysecure.keychain.service.ImportKeyringParcel;
import org.sufficientlysecure.keychain.ui.adapter.KeyListAdapter;
import org.sufficientlysecure.keychain.ui.base.CryptoOperationHelper;
import org.sufficientlysecure.keychain.ui.util.KeyFormattingUtils;
import org.sufficientlysecure.keychain.ui.util.Notify;
import org.sufficientlysecure.keychain.util.FabContainer;
import org.sufficientlysecure.keychain.util.Log;
import org.sufficientlysecure.keychain.util.Preferences;

import java.io.IOException;
import java.util.ArrayList;

/**
 * Public key list with sticky list headers. It does _not_ extend ListFragment because it uses
 * StickyListHeaders library which does not extend upon ListView.
 */
public class KeyListFragment extends LoaderFragment
        implements SearchView.OnQueryTextListener, KeyListAdapter.OnClickListener,
        LoaderManager.LoaderCallbacks<Cursor>, FabContainer, ActionMode.Callback, CryptoOperationHelper.Callback<ImportKeyringParcel, ImportKeyResult> {

    static final int REQUEST_ACTION = 1;
    private static final int REQUEST_DELETE = 2;
    private static final int REQUEST_VIEW_KEY = 3;

    private RecyclerView mRecyclerView;
    private KeyListAdapter mAdapter;

    // saves the mode object for multiselect, needed for reset at some point
    private ActionMode mActionMode = null;

    private Button vSearchButton;
    private ViewAnimator vSearchContainer;
    private String mQuery;

    private FloatingActionsMenu mFab;

    // for CryptoOperationHelper import
    private ArrayList<ParcelableKeyRing> mKeyList;
    private String mKeyserver;
    private CryptoOperationHelper<ImportKeyringParcel, ImportKeyResult> mImportOpHelper;

    // for ConsolidateOperation
    private CryptoOperationHelper<ConsolidateInputParcel, ConsolidateResult> mConsolidateOpHelper;

    /**
     * Load custom layout with StickyListView from library
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup superContainer, Bundle savedInstanceState) {
        View root = super.onCreateView(inflater, superContainer, savedInstanceState);
        View view = inflater.inflate(R.layout.key_list_fragment, getContainer());

        mRecyclerView = (RecyclerView) view.findViewById(R.id.key_list_recycler);
        mRecyclerView.setHasFixedSize(true);
        mRecyclerView.setLayoutManager(new LayoutManager(getActivity()));

        mFab = (FloatingActionsMenu) view.findViewById(R.id.fab_main);

        FloatingActionButton fabQrCode = (FloatingActionButton) view.findViewById(R.id.fab_add_qr_code);
        FloatingActionButton fabCloud = (FloatingActionButton) view.findViewById(R.id.fab_add_cloud);
        FloatingActionButton fabFile = (FloatingActionButton) view.findViewById(R.id.fab_add_file);

        fabQrCode.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                mFab.collapse();
                scanQrCode();
            }
        });
        fabCloud.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                mFab.collapse();
                searchCloud();
            }
        });
        fabFile.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                mFab.collapse();
                importFile();
            }
        });


        return root;
    }

    /**
     * Define Adapter and Loader on create of Activity
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        // show app name instead of "keys" from nav drawer
        final FragmentActivity activity = getActivity();

        activity.setTitle(R.string.app_name);

        // We have a menu item to show in action bar.
        setHasOptionsMenu(true);

        // Start out with a progress indicator.
        setContentShown(false);

        // click on search button (in empty view) starts query for search string
        vSearchContainer = (ViewAnimator) activity.findViewById(R.id.search_container);
        vSearchButton = (Button) activity.findViewById(R.id.search_button);
        vSearchButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                startSearchForQuery();
            }
        });

        // Create an empty adapter we will use to display the loaded data.
        mAdapter = new KeyListAdapter(getActivity());
        mAdapter.setOnClickListener(this);
        mRecyclerView.setAdapter(mAdapter);

        // Prepare the loader. Either re-connect with an existing one,
        // or start a new one.
        getLoaderManager().initLoader(0, null, this);
    }

    private void startSearchForQuery() {
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }

        Intent searchIntent = new Intent(activity, ImportKeysActivity.class);
        searchIntent.putExtra(ImportKeysActivity.EXTRA_QUERY, mQuery);
        searchIntent.setAction(ImportKeysActivity.ACTION_IMPORT_KEY_FROM_KEYSERVER);
        startActivity(searchIntent);
    }

    // These are the rows that we will retrieve.
    static final String[] PROJECTION = new String[]{
            KeyRings._ID,
            KeyRings.MASTER_KEY_ID,
            KeyRings.USER_ID,
            KeyRings.IS_REVOKED,
            KeyRings.IS_EXPIRED,
            KeyRings.VERIFIED,
            KeyRings.HAS_ANY_SECRET,
            KeyRings.HAS_DUPLICATE_USER_ID,
    };

    static final String ORDER =
            KeyRings.HAS_ANY_SECRET + " DESC, UPPER(" + KeyRings.USER_ID + ") ASC";

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        // This is called when a new Loader needs to be created. This
        // sample only has one Loader, so we don't care about the ID.
        Uri uri;
        if (!TextUtils.isEmpty(mQuery)) {
            uri = KeyRings.buildUnifiedKeyRingsFindByUserIdUri(mQuery);
        } else {
            uri = KeyRings.buildUnifiedKeyRingsUri();
        }

        // Now create and return a CursorLoader that will take care of
        // creating a Cursor for the data being displayed.
        return new CursorLoader(getActivity(), uri, PROJECTION, null, null, ORDER);
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        // Swap the new cursor in. (The framework will take care of closing the
        // old cursor once we return.)
        mAdapter.setSearchQuery(mQuery);

        if (data != null && (mQuery == null || TextUtils.isEmpty(mQuery))) {
            boolean isSecret = data.moveToFirst() && data.getInt(KeyListAdapter.INDEX_HAS_ANY_SECRET) != 0;
            if (!isSecret) {
                MatrixCursor headerCursor = new MatrixCursor(KeyListAdapter.PROJECTION);
                Long[] row = new Long[KeyListAdapter.PROJECTION.length];
                row[KeyListAdapter.INDEX_HAS_ANY_SECRET] = 1L;
                row[KeyListAdapter.INDEX_MASTER_KEY_ID] = 0L;
                headerCursor.addRow(row);

                Cursor dataCursor = data;
                data = new MergeCursor(new Cursor[]{
                        headerCursor, dataCursor
                });
            }
        }
        mAdapter.swapCursor(data);
        mRecyclerView.setAdapter(mAdapter);

        // this view is made visible if no data is available
        LinearLayout emptyLayout = (LinearLayout) getActivity().findViewById(R.id.key_list_empty);
        if (mAdapter.getItemCount() == 0) {
            mRecyclerView.setVisibility(View.GONE);
            emptyLayout.setVisibility(View.VISIBLE);
        } else {
            emptyLayout.setVisibility(View.GONE);
            mRecyclerView.setVisibility(View.VISIBLE);
        }

        // end action mode, if any
        if (mActionMode != null) {
            mActionMode.finish();
        }

        // The list should now be shown.
        if (isResumed()) {
            setContentShown(true);
        } else {
            setContentShownNoAnimation(true);
        }
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
        // This is called when the last Cursor provided to onLoadFinished()
        // above is about to be closed. We need to make sure we are no
        // longer using it.
        mAdapter.swapCursor(null);
    }

    @Override
    public void onImportClick() {
        importFile();
    }

    @Override
    public void onKeySlingerClick(KeyListAdapter.KeyHolder holder, int position) {
        Intent safeSlingerIntent = new Intent(getActivity(), SafeSlingerActivity.class);
        safeSlingerIntent.putExtra(SafeSlingerActivity.EXTRA_MASTER_KEY_ID, holder.getMasterKeyId());
        startActivityForResult(safeSlingerIntent, 0);
    }

    @Override
    public void onKeyClick(KeyListAdapter.KeyHolder holder, int position) {
        if (mActionMode == null) {
            Intent viewIntent = new Intent(getActivity(), ViewKeyActivity.class);
            viewIntent.setData(KeychainContract.KeyRings.buildGenericKeyRingUri(holder.getMasterKeyId()));
            startActivity(viewIntent);
        } else {
            mAdapter.toggleSelection(position);
            updateSelectionCount();
        }
    }

    @Override
    public void onKeyLongClick(KeyListAdapter.KeyHolder holder, int position) {
        if (mActionMode == null) {
            ((ActionBarActivity) getActivity()).startSupportActionMode(this);
        }

        mAdapter.toggleSelection(position);
        updateSelectionCount();
    }

    protected void encrypt(ActionMode mode, long[] masterKeyIds) {
        Intent intent = new Intent(getActivity(), EncryptFilesActivity.class);
        intent.setAction(EncryptFilesActivity.ACTION_ENCRYPT_DATA);
        intent.putExtra(EncryptFilesActivity.EXTRA_ENCRYPTION_KEY_IDS, masterKeyIds);
        // used instead of startActivity set actionbar based on callingPackage
        startActivityForResult(intent, REQUEST_ACTION);

        mode.finish();
    }

    /**
     * Show dialog to delete key
     *
     * @param hasSecret must contain whether the list of masterKeyIds contains a secret key or not
     */
    public void showDeleteKeyDialog(final ActionMode mode, long[] masterKeyIds, boolean hasSecret) {
        Intent intent = new Intent(getActivity(), DeleteKeyDialogActivity.class);
        intent.putExtra(DeleteKeyDialogActivity.EXTRA_DELETE_MASTER_KEY_IDS, masterKeyIds);
        intent.putExtra(DeleteKeyDialogActivity.EXTRA_HAS_SECRET, hasSecret);
        if (hasSecret) {
            intent.putExtra(DeleteKeyDialogActivity.EXTRA_KEYSERVER,
                    Preferences.getPreferences(getActivity()).getPreferredKeyserver());
        }
        startActivityForResult(intent, REQUEST_DELETE);
    }



    @Override
    public void onCreateOptionsMenu(final Menu menu, final MenuInflater inflater) {
        inflater.inflate(R.menu.key_list, menu);

        if (Constants.DEBUG) {
            menu.findItem(R.id.menu_key_list_debug_cons).setVisible(true);
            menu.findItem(R.id.menu_key_list_debug_bench).setVisible(true);
            menu.findItem(R.id.menu_key_list_debug_read).setVisible(true);
            menu.findItem(R.id.menu_key_list_debug_write).setVisible(true);
            menu.findItem(R.id.menu_key_list_debug_first_time).setVisible(true);
        }

        // Get the searchview
        MenuItem searchItem = menu.findItem(R.id.menu_key_list_search);

        SearchView searchView = (SearchView) MenuItemCompat.getActionView(searchItem);

        // Execute this when searching
        searchView.setOnQueryTextListener(this);

        // Erase search result without focus
        MenuItemCompat.setOnActionExpandListener(searchItem, new MenuItemCompat.OnActionExpandListener() {
            @Override
            public boolean onMenuItemActionExpand(MenuItem item) {

                // disable swipe-to-refresh
                // mSwipeRefreshLayout.setIsLocked(true);
                return true;
            }

            @Override
            public boolean onMenuItemActionCollapse(MenuItem item) {
                mQuery = null;
                getLoaderManager().restartLoader(0, null, KeyListFragment.this);

                // enable swipe-to-refresh
                // mSwipeRefreshLayout.setIsLocked(false);
                return true;
            }
        });

        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {

            case R.id.menu_key_list_create:
                createKey();
                return true;

            case R.id.menu_key_list_update_all_keys:
                updateAllKeys();
                return true;

            case R.id.menu_key_list_debug_read:
                try {
                    KeychainDatabase.debugBackup(getActivity(), true);
                    Notify.create(getActivity(), "Restored debug_backup.db", Notify.Style.OK).show();
                    getActivity().getContentResolver().notifyChange(KeychainContract.KeyRings.CONTENT_URI, null);
                } catch (IOException e) {
                    Log.e(Constants.TAG, "IO Error", e);
                    Notify.create(getActivity(), "IO Error " + e.getMessage(), Notify.Style.ERROR).show();
                }
                return true;

            case R.id.menu_key_list_debug_write:
                try {
                    KeychainDatabase.debugBackup(getActivity(), false);
                    Notify.create(getActivity(), "Backup to debug_backup.db completed", Notify.Style.OK).show();
                } catch (IOException e) {
                    Log.e(Constants.TAG, "IO Error", e);
                    Notify.create(getActivity(), "IO Error: " + e.getMessage(), Notify.Style.ERROR).show();
                }
                return true;

            case R.id.menu_key_list_debug_first_time:
                Preferences prefs = Preferences.getPreferences(getActivity());
                prefs.setFirstTime(true);
                Intent intent = new Intent(getActivity(), CreateKeyActivity.class);
                intent.putExtra(CreateKeyActivity.EXTRA_FIRST_TIME, true);
                startActivity(intent);
                getActivity().finish();
                return true;

            case R.id.menu_key_list_debug_cons:
                consolidate();
                return true;

            case R.id.menu_key_list_debug_bench:
                benchmark();
                return true;

            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public boolean onQueryTextSubmit(String s) {
        return true;
    }

    @Override
    public boolean onQueryTextChange(String s) {
        Log.d(Constants.TAG, "onQueryTextChange s:" + s);
        // Called when the action bar search text has changed.  Update the
        // search filter, and restart the loader to do a new query with this
        // filter.
        // If the nav drawer is opened, onQueryTextChange("") is executed.
        // This hack prevents restarting the loader.
        if (!s.equals(mQuery)) {
            mQuery = s;
            getLoaderManager().restartLoader(0, null, this);
        }

        if (s.length() > 2) {
            vSearchButton.setText(getString(R.string.btn_search_for_query, mQuery));
            vSearchContainer.setDisplayedChild(1);
            vSearchContainer.setVisibility(View.VISIBLE);
        } else {
            vSearchContainer.setDisplayedChild(0);
            vSearchContainer.setVisibility(View.GONE);
        }

        return true;
    }

    private void searchCloud() {
        Intent importIntent = new Intent(getActivity(), ImportKeysActivity.class);
        importIntent.putExtra(ImportKeysActivity.EXTRA_QUERY, (String) null); // hack to show only cloud tab
        startActivity(importIntent);
    }

    private void scanQrCode() {
        Intent scanQrCode = new Intent(getActivity(), ImportKeysProxyActivity.class);
        scanQrCode.setAction(ImportKeysProxyActivity.ACTION_SCAN_IMPORT);
        startActivityForResult(scanQrCode, REQUEST_ACTION);
    }

    private void importFile() {
        Intent intentImportExisting = new Intent(getActivity(), ImportKeysActivity.class);
        intentImportExisting.setAction(ImportKeysActivity.ACTION_IMPORT_KEY_FROM_FILE_AND_RETURN);
        startActivityForResult(intentImportExisting, REQUEST_ACTION);
    }

    private void createKey() {
        Intent intent = new Intent(getActivity(), CreateKeyActivity.class);
        startActivityForResult(intent, REQUEST_ACTION);
    }

    private void updateAllKeys() {
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }

        ProviderHelper providerHelper = new ProviderHelper(activity);

        Cursor cursor = providerHelper.getContentResolver().query(
                KeyRings.buildUnifiedKeyRingsUri(), new String[]{
                        KeyRings.FINGERPRINT
                }, null, null, null
        );

        if (cursor == null) {
            Notify.create(activity, R.string.error_loading_keys, Notify.Style.ERROR);
            return;
        }

        ArrayList<ParcelableKeyRing> keyList = new ArrayList<>();
        try {
            while (cursor.moveToNext()) {
                byte[] blob = cursor.getBlob(0);//fingerprint column is 0
                String fingerprint = KeyFormattingUtils.convertFingerprintToHex(blob);
                ParcelableKeyRing keyEntry = new ParcelableKeyRing(fingerprint, null);
                keyList.add(keyEntry);
            }
            mKeyList = keyList;
        } finally {
            cursor.close();
        }

        // search config
        mKeyserver = Preferences.getPreferences(getActivity()).getPreferredKeyserver();

        mImportOpHelper = new CryptoOperationHelper<>(1, this, this, R.string.progress_updating);
        mImportOpHelper.setProgressCancellable(true);
        mImportOpHelper.cryptoOperation();
    }

    private void consolidate() {

        CryptoOperationHelper.Callback<ConsolidateInputParcel, ConsolidateResult> callback
                = new CryptoOperationHelper.Callback<ConsolidateInputParcel, ConsolidateResult>() {

            @Override
            public ConsolidateInputParcel createOperationInput() {
                return new ConsolidateInputParcel(false); // we want to perform a full consolidate
            }

            @Override
            public void onCryptoOperationSuccess(ConsolidateResult result) {
                result.createNotify(getActivity()).show();
            }

            @Override
            public void onCryptoOperationCancelled() {

            }

            @Override
            public void onCryptoOperationError(ConsolidateResult result) {
                result.createNotify(getActivity()).show();
            }

            @Override
            public boolean onCryptoSetProgress(String msg, int progress, int max) {
                return false;
            }
        };

        mConsolidateOpHelper =
                new CryptoOperationHelper<>(2, this, callback, R.string.progress_importing);

        mConsolidateOpHelper.cryptoOperation();
    }

    private void benchmark() {

        CryptoOperationHelper.Callback<BenchmarkInputParcel, BenchmarkResult> callback
                = new CryptoOperationHelper.Callback<BenchmarkInputParcel, BenchmarkResult>() {

            @Override
            public BenchmarkInputParcel createOperationInput() {
                return new BenchmarkInputParcel(); // we want to perform a full consolidate
            }

            @Override
            public void onCryptoOperationSuccess(BenchmarkResult result) {
                result.createNotify(getActivity()).show();
            }

            @Override
            public void onCryptoOperationCancelled() {

            }

            @Override
            public void onCryptoOperationError(BenchmarkResult result) {
                result.createNotify(getActivity()).show();
            }

            @Override
            public boolean onCryptoSetProgress(String msg, int progress, int max) {
                return false;
            }
        };

        CryptoOperationHelper opHelper =
                new CryptoOperationHelper<>(2, this, callback, R.string.progress_importing);

        opHelper.cryptoOperation();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (mImportOpHelper != null) {
            mImportOpHelper.handleActivityResult(requestCode, resultCode, data);
        }

        if (mConsolidateOpHelper != null) {
            mConsolidateOpHelper.handleActivityResult(requestCode, resultCode, data);
        }

        switch (requestCode) {
            case REQUEST_DELETE:
                if (mActionMode != null) {
                    mActionMode.finish();
                }
                if (data != null && data.hasExtra(OperationResult.EXTRA_RESULT)) {
                    OperationResult result = data.getParcelableExtra(OperationResult.EXTRA_RESULT);
                    result.createNotify(getActivity()).show();
                } else {
                    super.onActivityResult(requestCode, resultCode, data);
                }
                break;

            case REQUEST_ACTION:
                // if a result has been returned, display a notify
                if (data != null && data.hasExtra(OperationResult.EXTRA_RESULT)) {
                    OperationResult result = data.getParcelableExtra(OperationResult.EXTRA_RESULT);
                    result.createNotify(getActivity()).show();
                } else {
                    super.onActivityResult(requestCode, resultCode, data);
                }
                break;

            case REQUEST_VIEW_KEY:
                if (data != null && data.hasExtra(OperationResult.EXTRA_RESULT)) {
                    OperationResult result = data.getParcelableExtra(OperationResult.EXTRA_RESULT);
                    result.createNotify(getActivity()).show();
                } else {
                    super.onActivityResult(requestCode, resultCode, data);
                }
                break;
        }
    }

    @Override
    public void fabMoveUp(int height) {
        ObjectAnimator anim = ObjectAnimator.ofFloat(mFab, "translationY", 0, -height);
        // we're a little behind, so skip 1/10 of the time
        anim.setDuration(270);
        anim.start();
    }

    @Override
    public void fabRestorePosition() {
        ObjectAnimator anim = ObjectAnimator.ofFloat(mFab, "translationY", 0);
        // we're a little ahead, so wait a few ms
        anim.setStartDelay(70);
        anim.setDuration(300);
        anim.start();
    }

    // CryptoOperationHelper.Callback methods
    @Override
    public ImportKeyringParcel createOperationInput() {
        return new ImportKeyringParcel(mKeyList, mKeyserver);
    }

    @Override
    public void onCryptoOperationSuccess(ImportKeyResult result) {
        result.createNotify(getActivity()).show();
    }

    @Override
    public void onCryptoOperationCancelled() {

    }

    @Override
    public void onCryptoOperationError(ImportKeyResult result) {
        result.createNotify(getActivity()).show();
    }

    @Override
    public boolean onCryptoSetProgress(String msg, int progress, int max) {
        return false;
    }

    @Override
    public boolean onCreateActionMode(ActionMode actionMode, Menu menu) {
        MenuInflater inflater = getActivity().getMenuInflater();
        inflater.inflate(R.menu.key_list_multi, menu);
        mActionMode = actionMode;
        return true;
    }

    @Override
    public boolean onPrepareActionMode(ActionMode actionMode, Menu menu) {
        return false;
    }

    @Override
    public boolean onActionItemClicked(ActionMode actionMode, MenuItem menuItem) {
        // get IDs for checked positions as long array
        long[] ids;

        switch (menuItem.getItemId()) {
            case R.id.menu_key_list_multi_encrypt: {
                ids = mAdapter.getCurrentSelectedMasterKeyIds();
                encrypt(actionMode, ids);
                break;
            }
            case R.id.menu_key_list_multi_delete: {
                ids = mAdapter.getCurrentSelectedMasterKeyIds();
                showDeleteKeyDialog(actionMode, ids, mAdapter.isAnySecretSelected());
                break;
            }
            case R.id.menu_key_list_multi_export: {
                ids = mAdapter.getCurrentSelectedMasterKeyIds();
                showMultiExportDialog(ids);
                break;
            }
            case R.id.menu_key_list_multi_select_all: {
                mAdapter.selectAll();
                updateSelectionCount();
                break;
            }
        }
        return true;
    }

    @Override
    public void onDestroyActionMode(ActionMode actionMode) {
        mActionMode = null;
        mAdapter.clearSelection();
    }

    private void updateSelectionCount() {
        int count = mAdapter.getSelectionCount();
        if (count == 0) {
            mActionMode.finish();
        } else {
            mActionMode.setTitle(getResources().getQuantityString(R.plurals.key_list_selected_keys, count, count));
        }
    }

}
