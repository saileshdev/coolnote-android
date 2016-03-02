package sailesh.notes.com.coolnote;

import android.app.AlertDialog;
import android.app.LoaderManager;
import android.content.ContentValues;
import android.content.CursorLoader;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.Loader;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.ActionBarActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.CursorAdapter;
import android.widget.ListView;
import android.widget.Toast;

import com.dropbox.client2.DropboxAPI;
import com.dropbox.client2.android.AndroidAuthSession;
import com.dropbox.client2.session.AppKeyPair;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;


public class MainActivity extends ActionBarActivity
implements LoaderManager.LoaderCallbacks<Cursor>
{
    private static final int EDITOR_REQUEST_CODE = 1001;

    final static private String APP_KEY = "INSERT_YOUR_APP_KEY_HERE";
    final static private String APP_SECRET = "INSERT_YOUR_SECRET_KEY_HERE";
    private DropboxAPI<AndroidAuthSession> mDBApi;

    private CursorAdapter cursorAdapter;
    private Cursor cursor;
    private SwipeRefreshLayout swipeContainer;

    public List<SyncTask> tasks;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // dropbox intialization
        AppKeyPair appKeys = new AppKeyPair(APP_KEY, APP_SECRET);
        AndroidAuthSession session = new AndroidAuthSession(appKeys);
        mDBApi = new DropboxAPI<AndroidAuthSession>(session);
        tasks = new ArrayList<>();

        cursorAdapter = new NotesCursorAdapter(this, null, 0);

        ListView list = (ListView) findViewById(android.R.id.list);
        list.setAdapter(cursorAdapter);

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Intent intent = new Intent(MainActivity.this, EditorActivity.class);
                Uri uri = Uri.parse(NotesProvider.CONTENT_URI + "/" + id);
                intent.putExtra(NotesProvider.CONTENT_ITEM_TYPE, uri);
                startActivityForResult(intent, EDITOR_REQUEST_CODE);
            }
        });

        getLoaderManager().initLoader(0, null, this);

        //swipe to refresh

        swipeContainer = (SwipeRefreshLayout) findViewById(R.id.swipeContainer);
        // Setup refresh listener which triggers new data loading
        swipeContainer.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                // Sync the data in dropbox

                mDBApi.getSession().startOAuth2Authentication(MainActivity.this);

            }
        });
        // Configure the refreshing colors
        swipeContainer.setColorSchemeResources(android.R.color.holo_blue_bright,
                android.R.color.holo_blue_light,
                android.R.color.holo_blue_dark);

    }

    private void insertNote(String noteText) {
        ContentValues values = new ContentValues();
        values.put(DBOpenHelper.NOTE_TEXT, noteText);
        Uri noteUri = getContentResolver().insert(NotesProvider.CONTENT_URI,
                values);
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        switch (id) {
            case R.id.action_create_sample:
                insertSampleData();
                break;
            case R.id.action_delete_all:
                deleteAllNotes();
                break;
        }

        return super.onOptionsItemSelected(item);
    }

    private void deleteAllNotes() {

        DialogInterface.OnClickListener dialogClickListener =
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int button) {
                        if (button == DialogInterface.BUTTON_POSITIVE) {
                            //Insert Data management code here
                            getContentResolver().delete(
                                    NotesProvider.CONTENT_URI, null, null
                            );
                            restartLoader();

                            Toast.makeText(MainActivity.this,
                                    getString(R.string.all_deleted),
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                };

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage(getString(R.string.are_you_sure))
                .setPositiveButton(getString(android.R.string.yes), dialogClickListener)
                .setNegativeButton(getString(android.R.string.no), dialogClickListener)
                .show();
    }

    private void insertSampleData() {
        insertNote("Simple note");
        insertNote("Multi-line\nnote");
        insertNote("Very long note with a lot of text that exceeds the width of the screen");
        restartLoader();
    }

    private void restartLoader() {
        getLoaderManager().restartLoader(0, null, this);
    }

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        return new CursorLoader(this, NotesProvider.CONTENT_URI,
                null, null, null, null);
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        cursorAdapter.swapCursor(data);
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
        cursorAdapter.swapCursor(null);
    }

    public void openEditorForNewNote(View view) {
        Intent intent = new Intent(this, EditorActivity.class);
        startActivityForResult(intent, EDITOR_REQUEST_CODE);

    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == EDITOR_REQUEST_CODE && resultCode == RESULT_OK) {
            restartLoader();
        }
    }

    public void SyncDropbox() {

        SyncTask task = new SyncTask();
        task.execute(cursorAdapter);
    }


    protected void onResume() {
        super.onResume();

        if (mDBApi.getSession().authenticationSuccessful()) {
            try {
                // Required to complete auth, sets the access token on the session
                mDBApi.getSession().finishAuthentication();
                //String accessToken = mDBApi.getSession().getOAuth2AccessToken();
                SyncDropbox();

            } catch (IllegalStateException e) {
                Log.i("DbAuthLog", "Error authenticating", e);
            }
        }
    }


    private class SyncTask extends AsyncTask<CursorAdapter, Void, String> {

        @Override
        protected void onPreExecute() {
            tasks.add(this);

        }

        @Override
        protected String doInBackground(CursorAdapter... params) {

            try {

                CursorAdapter cursor_adapter = params[0];

                for(int i=0;i<cursor_adapter.getCount();++i){

                    cursor = (Cursor)cursor_adapter.getItem(i);

                    String noteText = cursor.getString(
                            cursor.getColumnIndex(DBOpenHelper.NOTE_TEXT));

                    FileOutputStream fos = openFileOutput("file" + String.valueOf(i) + ".txt",MODE_PRIVATE);
                    fos.write(noteText.getBytes());

                    File file = new File(getFilesDir().getAbsolutePath(),"file" + String.valueOf(i) + ".txt");
                    FileInputStream inputStream = new FileInputStream(file);
                    DropboxAPI.Entry response = mDBApi.putFile("/file" + String.valueOf(i+1) + ".txt", inputStream, file.length(), null, null);

                }
                return null;

            }
            catch (Exception e){
                e.printStackTrace();
                Log.d("sailesh",e.toString());
                return null;
            }

        }

        @Override
        protected void onPostExecute(String result) {

            tasks.remove(this);
            if (tasks.size() == 0) {
                // end the swipecontainer
                swipeContainer.setRefreshing(false);
            }

        }




    }
}
