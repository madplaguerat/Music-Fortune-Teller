package hellhound.flamingoplayer;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.support.constraint.ConstraintLayout;
import android.support.v4.app.FragmentManager;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.PagerSnapHelper;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.Animation;
import android.view.animation.Interpolator;
import android.view.animation.Transformation;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.Switch;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Stack;

import de.umass.lastfm.Authenticator;
import de.umass.lastfm.Caller;
import de.umass.lastfm.Session;

public class MainActivity extends AppCompatActivity implements TopHeader.TopHeaderListener, PlayControls.PlayControlsListener,
        CenterPlayer.CenterPlayerListener{

    private final static String TAG = "main_activity";
    private static ArrayList<MenuItem> homeItems;
    private RecyclerView recyclerView;
    private RecyclerView playRecyclerView;
    private RecyclerView.LayoutManager layoutManagerVertical;
    private RecyclerView.LayoutManager layoutManagerHorizontal;
    private PagerSnapHelper snapHelper;
    private HomeScreenAdapter adapter;
    private PlayerAdapter playerAdapter;
    public DBHelper db;
    private ProgressBar progressBar;

    public enum STATES {HOME, ARTISTS, ALBUMS, TRACKS, PLAYLISTS, CHARTS}
    private Stack<STATES> state;
    private TopHeader topHeader;
    private PlayControls playControls;
    private CenterPlayer centerPlayer;
    private PlaylistItem currentPlayList;
    private boolean playerIsHidden = false;
    private boolean scrobbling = false;

    public final static String LASTFM_PREFS = "lastfm";
    public final static String LASTFM_USERNAME = "username";
    public final static String LASTFM_PASSWORD = "password";
    public final static String LASTFM_SK = "sk";
    public final static String LASTFM_SCROBBLING = "scrobbling";
    public LastfmHelper lastfmHelper;
    int screenHeight;

    //Service fields
    private MusicService musicService;
    private boolean isBound = false;
    private BroadcastReceiver broadcastReceiver;
    public final static String BROADCAST_ACTION = "playservice";

    public final static int TASK_PROGRESS = 0;
    public final static String PARAM_PROGRESS = "progress";
    public final static String PARAM_MAX = "max";
    public final static String PARAM_TASK = "task";

    public final static int TASK_INFO = 1;
    public final static String PARAM_TRACK_NUM = "track_number";

    static class LastFmHandler extends Handler{
        private final WeakReference<MainActivity> activityWeakReference;

        LastFmHandler(MainActivity activity){
            activityWeakReference = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(Message msg) {
            MainActivity activity = activityWeakReference.get();
            if(activity != null){
                switch (msg.what){
                    case LOGIN_SUCCESS:
                        activity.updateLastFmUser((String) ((ArrayList)msg.obj).get(0),
                                (String) ((ArrayList)msg.obj).get(1));
                        break;

                    case LOGIN_FAIL:
                        activity.sayLastFmUserNotSet();
                        break;
                }
                activity.switchProgressBar(false);
            }
        }
    }
    private LastFmHandler handler = new LastFmHandler(this);
    static final int LOGIN_SUCCESS = 1;
    static final int LOGIN_FAIL = 0;
    private void updateLastFmUser(String username, String password){
        Log.i(TAG, "Successfully logged in");
        SharedPreferences prefs = getSharedPreferences(LASTFM_PREFS, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(LASTFM_USERNAME, username);
        editor.putString(LASTFM_PASSWORD, password);
        editor.putString(LASTFM_SK, lastfmHelper.sk);
        Log.i(TAG, lastfmHelper.sk);
        editor.apply();
        Log.i(TAG, "Shared settings done");
        Toast.makeText(getApplicationContext(), "Successfully logged in", Toast.LENGTH_SHORT).show();
    }
    private void sayLastFmUserNotSet(){

        Log.i(TAG, "Could not log in");
        Toast.makeText(getApplicationContext(), "Could not log in", Toast.LENGTH_SHORT).show();
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setupProgressBar();
        topHeader = (TopHeader) getSupportFragmentManager().findFragmentById(R.id.fragment);
        playControls = (PlayControls) getSupportFragmentManager().findFragmentById(R.id.fragment2);
        centerPlayer = (CenterPlayer) getSupportFragmentManager().findFragmentById(R.id.fragment3);
        Display display = getWindowManager().getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);
        screenHeight = size.y;

        //Setting up LastFmHelper
        Log.i(TAG, "Initializing Lastfm");
        lastfmHelper = LastfmHelper.getInstance(getResources().getString(R.string.last_api_key),
                getResources().getString(R.string.last_shared_secret));
        SharedPreferences prefs = getSharedPreferences(LASTFM_PREFS, MODE_PRIVATE);
        String username = prefs.getString(LASTFM_USERNAME, "");
        String password = prefs.getString(LASTFM_PASSWORD, "");
        String sk = prefs.getString(LASTFM_SK, "");
        Log.i(TAG, username + " - " + password + " - " + sk);
        if (!username.equals("") && !password.equals("") && !sk.equals("")){
            lastfmHelper.username = username;
            lastfmHelper.password = password;
            lastfmHelper.sk = sk;
        }
        scrobbling = prefs.getBoolean(LASTFM_SCROBBLING, false);


        //Setting up RecyclerView, Database and States stack
        setHomeItems();
        currentPlayList = new PlaylistItem("Queue");
        state = new Stack<>();
        state.push(STATES.HOME);
        db = DBHelper.getInstance(getApplicationContext());

        recyclerView = (RecyclerView) findViewById(R.id.rv);
        layoutManagerVertical = new LinearLayoutManager(this);
        recyclerView.setLayoutManager(layoutManagerVertical);
        adapter = new HomeScreenAdapter(this, homeItems);
        recyclerView.setAdapter(adapter);

        snapHelper = new PagerSnapHelper();
        playRecyclerView = (RecyclerView) findViewById(R.id.rvPlay);
        layoutManagerHorizontal = new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false);
        playerAdapter = new PlayerAdapter(this, currentPlayList.getTracks());
        playRecyclerView.setLayoutManager(layoutManagerHorizontal);
        playRecyclerView.setAdapter(playerAdapter);
        playRecyclerView.setTranslationY(screenHeight);
        snapHelper.attachToRecyclerView(playRecyclerView);

        playRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                if(dx >= 0){
                    int lastCompVis = ((LinearLayoutManager) layoutManagerHorizontal).findLastCompletelyVisibleItemPosition();
                    if((currentPlayList.getCurrentTrack() != lastCompVis) && (lastCompVis != -1)){
                        playNext();
                    }
                } else {
                    int firstCompVis = ((LinearLayoutManager) layoutManagerHorizontal).findFirstCompletelyVisibleItemPosition();
                    if((currentPlayList.getCurrentTrack() != firstCompVis) && firstCompVis != -1){
                        playPrev(true);
                    }
                }
            }


        });

        switchToPlayer();
        db.close();

        //Setting up MusicService
        Intent musicIntent = new Intent(this, MusicService.class);
        bindService(musicIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int task = intent.getIntExtra(PARAM_TASK, 0);

                switch (task){
                    case TASK_PROGRESS:
                        int progress = intent.getIntExtra(PARAM_PROGRESS, 0);
                        int max = intent.getIntExtra(PARAM_MAX, 0);
                        topHeader.setProgressSquare(progress, max);
                        centerPlayer.setTime(progress, max);
                        break;

                    case TASK_INFO:
                        int num = intent.getIntExtra(PARAM_TRACK_NUM, 0);
                        currentPlayList.setCurrentTrack(num);
                        playControls.setTrack(currentPlayList.getTrack(num));
                        layoutManagerHorizontal.scrollToPosition(num);
                        break;
                }

            }
        };
        IntentFilter intentFilter = new IntentFilter(BROADCAST_ACTION);
        registerReceiver(broadcastReceiver, intentFilter);
    }


    private void setHomeItems(){
        homeItems = new ArrayList<>();
        homeItems.add(new HomeScreenItem("Artists"));
        homeItems.add(new HomeScreenItem("Albums"));
        homeItems.add(new HomeScreenItem("Tracks"));
        homeItems.add(new HomeScreenItem("Playlists"));
        homeItems.add(new HomeScreenItem("Queue"));
    }

    public  ArrayList<MenuItem> getHomeItems(){
        return homeItems;
    }

    public boolean switchToPlayer(){
        if(playerIsHidden){
            FragmentManager fm = getSupportFragmentManager();
            fm.beginTransaction()
                    .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
                    .show(centerPlayer)
                    .commit();
            playerIsHidden = !playerIsHidden;
            playRecyclerView.animate().translationY(0);
            recyclerView.animate().translationY(-screenHeight);
            topHeader.switchBackButton(STATES.ARTISTS);
            return playerIsHidden;
        } else {
            FragmentManager fm = getSupportFragmentManager();
            fm.beginTransaction()
                    .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
                    .hide(centerPlayer)
                    .commit();
            playerIsHidden = !playerIsHidden;
            playRecyclerView.animate().translationY(screenHeight);
            recyclerView.animate().translationY(0);
            topHeader.switchBackButton(getState());
            return playerIsHidden;
        }
    }


    @Override
    public void onBackPressed() {
        if((getState() == STATES.HOME) && (playerIsHidden)){
            super.onBackPressed();
        } else {
            backButtonClicked();
        }
    }


    @Override
    protected void onDestroy() {
        musicService.onDestroy();
        super.onDestroy();
    }

    /*--------------------------------------------------------------------------------------------*/
    /*------------------------------- Methods for state managing ---------------------------------*/
    /*--------------------------------------------------------------------------------------------*/
    public STATES getState(){
        return state.peek();
    }

    public STATES changeStateBack(){
        STATES oldState = getState();
        if (oldState != STATES.HOME){
            oldState = state.pop();
        }

        STATES currState = getState();
        topHeader.switchBackButton(currState);
        topHeader.changeTitleText(currState);
        return oldState;
    }

    public void changeStateNext(STATES newState){
        state.push(newState);
        topHeader.switchBackButton(newState);
        topHeader.changeTitleText(newState);
    }


    /*--------------------------------------------------------------------------------------------*/
    /*----------------------------------- Methods for Fragments ----------------------------------*/
    /*--------------------------------------------------------------------------------------------*/

    @Override
    public void backButtonClicked() {
        if(playerIsHidden)
        {
            while (getState() != STATES.HOME){
                changeStateBack();
            }
            changeStateNext(STATES.ARTISTS);
            adapter.handleClicks(1, HomeScreenAdapter.ACTIONS.BACK);
        } else {
            switchToPlayer();
        }

    }

    @Override
    public void setLastfm() {
        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(this);
        final View dialogView = this.getLayoutInflater().inflate(R.layout.set_lastfm, null);
        dialogBuilder.setView(dialogView);

        final EditText user = (EditText) dialogView.findViewById(R.id.user);
        final EditText password = (EditText) dialogView.findViewById(R.id.password);
        final Switch sw = (Switch) dialogView.findViewById(R.id.enable_scrobbling);
        Button logoff = (Button) dialogView.findViewById(R.id.log_off);

        SharedPreferences prefs = getSharedPreferences(LASTFM_PREFS, MODE_PRIVATE);
        String username = prefs.getString(LASTFM_USERNAME, "");
        String _password = prefs.getString(LASTFM_PASSWORD, "");
        if (!username.equals("") && !_password.equals("")){
            user.setText(username);
            password.setText(_password);
        }

        if(scrobbling){
            sw.setText(R.string.diable_scrobbling);
            sw.setChecked(true);
        } else {
            sw.setText(R.string.enable_scrobbling);
            sw.setChecked(false);
        }

        dialogBuilder.setTitle("Last.fm scrobbling");

        dialogBuilder.setPositiveButton("Done", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int whichButton) {
                Log.i(TAG, "Clicked Done");
                final String username = user.getText().toString();
                final String passw = password.getText().toString();

                if((lastfmHelper.password.equals(passw)) && (lastfmHelper.username.equals(username))){
                    return;
                }

                Thread thread = new Thread(new Runnable() {
                    Message msg;
                    @Override
                    public void run() {
                        boolean logged = lastfmHelper.login(username, passw);
                        if(logged){
                            ArrayList<String> logpass = new ArrayList<>();
                            logpass.add(username);
                            logpass.add(passw);
                            msg = handler.obtainMessage(LOGIN_SUCCESS, logpass);
                            handler.sendMessage(msg);
                        } else {
                            handler.sendEmptyMessage(LOGIN_FAIL);
                        }
                    }
                });
                switchProgressBar(true);
                thread.start();
            }
        });
        dialogBuilder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int whichButton) {
                //pass
            }
        });
        sw.setOnCheckedChangeListener(
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        enableScrobbling(isChecked);
                        if(isChecked){
                            sw.setText(R.string.diable_scrobbling);
                        } else {
                            sw.setText(R.string.enable_scrobbling);
                        }
                    }
                }
        );
        logoff.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        SharedPreferences pref = getSharedPreferences(LASTFM_PREFS, MODE_PRIVATE);
                        SharedPreferences.Editor editor = pref.edit();
                        editor.clear();
                        editor.apply();
                        sw.setText(R.string.enable_scrobbling);
                        sw.setChecked(false);
                        enableScrobbling(false);
                    }
                }
        );

        AlertDialog b = dialogBuilder.create();
        b.show();
    }

    @Override
    public void controlButtonClicked(PlayControls.CONTROLS action) {
        switch (action){
            case PLAY:
                play();
                break;

            case NEXT:
                playNext();
                break;

            case PREV:
                playPrev(false);
                break;

        }
    }

    @Override
    public void seekBarChanged(int progress) {
        if(currentPlayList.getSize() > 0){
            musicService.setProgress(progress);
        }
    }

    @Override
    public boolean openClosePlayer() {
        return switchToPlayer();
    }

    /*--------------------------------------------------------------------------------------------*/
    /*--------------------------------- Methods for MusicService ---------------------------------*/
    /*--------------------------------------------------------------------------------------------*/

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(TAG, "Connected?");
            MusicService.LocalBinder binder = (MusicService.LocalBinder) service;
            musicService = binder.getService();
            musicService.initPlayer();
            isBound = true;
            Log.i(TAG, "onServiceConnected");

        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
        }
    };

    /*--------------------------------------------------------------------------------------------*/
    /*----------------------------------- Methods for playback -----------------------------------*/
    /*--------------------------------------------------------------------------------------------*/

    public void setNewPlaylist(ArrayList<MenuItem> items, int currentTrack){
        currentPlayList.clearTracks();
        currentPlayList.setTracks(items, currentTrack);
        musicService.prepareTracks(currentPlayList.getTracksPaths());
        musicService.seekToWindow(currentPlayList.getCurrentTrack());
        playerAdapter.setItems(currentPlayList.getTracks());
        layoutManagerHorizontal.scrollToPosition(currentPlayList.getCurrentTrack());
        Log.i(TAG, "Prepared player");
    }

    public boolean play(){
        if(currentPlayList.getSize() > 0){
            boolean isPlaying = musicService.play();
            playControls.setPlayButton(isPlaying);
            return isPlaying;
        } else {
            return false;
        }
    }

    public boolean play(boolean play){
        if(currentPlayList.getSize() > 0) {
            boolean isPlaying = musicService.play(play);
            playControls.setPlayButton(isPlaying);
            return isPlaying;
        } else {
            return false;
        }
    }

    public int playNext(){
        if(currentPlayList.getSize() > 0) {
            int nextTrack = musicService.nextTrack();
            currentPlayList.setCurrentTrack(nextTrack);
            return nextTrack;
        } else {
            return 0;
        }
    }

    public int playPrev(boolean force){
        if(currentPlayList.getSize() > 0) {
            int nextTrack = musicService.prevTrack(force);
            currentPlayList.setCurrentTrack(nextTrack);
            return nextTrack;
        } else {
            return 0;
        }
    }

    /*--------------------------------------------------------------------------------------------*/
    /*---------------------------------- Methods for scrobbling ----------------------------------*/
    /*--------------------------------------------------------------------------------------------*/

    public boolean enableScrobbling(boolean enable){
        SharedPreferences prefs = getSharedPreferences(LASTFM_PREFS, MODE_PRIVATE);
        SharedPreferences.Editor edit = prefs.edit();
        if(enable){
            if(prefs.contains(LASTFM_USERNAME)){
                scrobbling = true;
                edit.putBoolean(LASTFM_SCROBBLING, true);
                edit.apply();
                return true;
            } else {
                edit.putBoolean(LASTFM_SCROBBLING, false);
                edit.apply();
                return false;
            }
        } else {
            scrobbling = false;
            edit.putBoolean(LASTFM_SCROBBLING, false);
            edit.apply();
            return false;
        }
    }

    /*--------------------------------------------------------------------------------------------*/
    /*--------------------------------------------- Misc -----------------------------------------*/
    /*--------------------------------------------------------------------------------------------*/

   public void setupProgressBar(){
        progressBar = new ProgressBar(getApplicationContext(), null, android.R.attr.progressBarStyleHorizontal);
        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(100,100);
        params.addRule(RelativeLayout.CENTER_IN_PARENT);
        ConstraintLayout layout = findViewById(R.id.main);
        layout.addView(progressBar);
        progressBar.setVisibility(View.GONE);
   }

   public void switchProgressBar(boolean enable){
       if(enable){
           progressBar.setVisibility(View.VISIBLE);
           getWindow().setFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                   WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
       } else {
           progressBar.setVisibility(View.GONE);
           getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
       }
   }

}
