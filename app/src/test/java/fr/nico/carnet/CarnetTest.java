package fr.nico.carnet;

import android.app.AlertDialog;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class CarnetTest {
    NoteStore store;
    @Before public void setup() {
        Context context=RuntimeEnvironment.getApplication(); context.deleteDatabase("carnet.db");
        context.getSharedPreferences("MainActivity",0).edit().clear().commit(); store=new NoteStore(context);
    }
    @After public void close() { store.close(); }
    private void edit(MainActivity activity,long id) throws Exception {
        Method method=MainActivity.class.getDeclaredMethod("showEditor",long.class);method.setAccessible(true);method.invoke(activity,id);
    }
    @Test public void saveAndRestoreKeepEveryVersion() {
        long id=store.create("Première idée");
        assertTrue(store.save(id,"Deuxième idée")); assertFalse(store.save(id,"Deuxième idée"));
        store.save(id,store.history(id).get(1).body());
        assertEquals("Première idée",store.get(id).body()); assertEquals(3,store.history(id).size());
        assertEquals("Deuxième idée",store.history(id).get(1).body());
    }
    @Test public void deletionPermanentlyRemovesOnlyThatNoteAndItsHistory() throws Exception {
        long id=store.create("À supprimer"), keep=store.create("À garder");store.save(id,"Version suivante");store.remove(id);
        store.close();store=new NoteStore(RuntimeEnvironment.getApplication());
        assertNull(store.get(id));assertTrue(store.history(id).isEmpty());assertEquals(1,store.list("").size());assertNotNull(store.get(keep));
        assertEquals(1,store.backup().getJSONArray("notes").length());
    }
    @Test public void longPressOffersCancelAndPermanentDelete() {
        long id=store.create("Cette note reste jusqu’à confirmation");store.save(id,"Une deuxième version");
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup();MainActivity activity=controller.get();
        View card=activity.findViewById(android.R.id.content).findViewWithTag(id);assertNotNull(card);assertTrue(card.performLongClick());
        assertTrue(card.isSelected());assertNull(ShadowAlertDialog.getLatestAlertDialog());
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(180));
        AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertTrue(dialog.isShowing());
        assertEquals("Supprimer",dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());assertNotNull(store.get(id));
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertNotNull(store.get(id));assertFalse(card.isSelected());
        card.performLongClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(180));ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertNull(store.get(id));assertTrue(store.history(id).isEmpty());assertNull(activity.findViewById(android.R.id.content).findViewWithTag(id));
        controller.pause().stop().destroy();assertNull(store.get(id));
    }
    @Test public void homeAndEditorHaveNoTitleFavoritesTrashOrSubtitle() throws Exception {
        long id=store.create("Texte uniquement");ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup();MainActivity activity=controller.get();
        String home=visibleText(activity.findViewById(android.R.id.content));
        assertFalse(home.contains("Favoris"));assertFalse(home.contains("Corbeille"));assertFalse(home.contains("Une idée, une note."));assertTrue(home.contains("Texte uniquement"));
        edit(activity,id);assertNull(activity.findViewById(101));assertEquals(1,editors(activity.findViewById(android.R.id.content)));
        assertFalse(visibleText(activity.findViewById(android.R.id.content)).contains("Liste"));
        assertEquals("Texte uniquement",((EditText)activity.findViewById(102)).getText().toString());controller.pause().stop().destroy();
    }
    private String visibleText(View view) {
        StringBuilder text=new StringBuilder();if(view instanceof TextView)text.append(((TextView)view).getText()).append('\n');
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)text.append(visibleText(group.getChildAt(i)));}return text.toString();
    }
    private int editors(View view) {
        int count=view instanceof EditText?1:0;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)count+=editors(group.getChildAt(i));}return count;
    }
    @Test public void searchTreatsSqlAndWildcardsAsText() {
        store.create("Budget 50% : a_b et ' OR 1=1 --"); store.create("Autre texte");
        assertEquals(1,store.list("%").size());assertEquals(1,store.list("_").size());assertTrue(store.list("x' OR 1=1 --").isEmpty());assertEquals(2,store.list("").size());
    }
    @Test public void backupRoundTripKeepsTextAndHistoryWithoutRemovedFields() throws Exception {
        long id=store.create("éè日本語");store.save(id,"Texte modifié");JSONObject backup=store.backup();
        assertEquals(2,backup.getInt("version"));JSONObject note=backup.getJSONArray("notes").getJSONObject(0);
        assertFalse(note.has("title"));assertFalse(note.has("pinned"));assertFalse(note.has("deleted"));
        assertEquals(1,store.importBackup(backup));assertEquals(2,store.list("").size());long copy=store.list("").get(0).id();assertNotEquals(id,copy);
        assertEquals("éè日本語",store.history(copy).get(1).body());assertEquals("Texte modifié",store.get(copy).body());
    }
    @Test public void legacyBackupPreservesTitlesAndFormerTrashAsText() throws Exception {
        JSONObject backup=new JSONObject("{\"format\":\"carnet\",\"version\":1,\"notes\":[{\"title\":\"Ancien titre\",\"body\":\"Texte\",\"updated\":200,\"pinned\":true,\"deleted\":true,\"versions\":[{\"title\":\"Ancien titre\",\"body\":\"Texte\",\"saved\":200},{\"title\":\"Premier titre\",\"body\":\"\",\"saved\":100}]}]}");
        assertEquals(1,store.importBackup(backup));long id=store.list("").get(0).id();
        assertEquals("Ancien titre\n\nTexte",store.get(id).body());assertEquals("Premier titre",store.history(id).get(1).body());
    }
    @Test @Config(sdk=26) public void migrationPreservesExistingNotesAndHistoryOnOldestSupportedAndroid() {
        store.close();Context context=RuntimeEnvironment.getApplication();
        try(SQLiteDatabase db=context.openOrCreateDatabase("carnet.db",0,null)) {
            db.execSQL("CREATE TABLE notes(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,body TEXT NOT NULL,updated INTEGER NOT NULL,pinned INTEGER NOT NULL DEFAULT 0,deleted INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE TABLE versions(id INTEGER PRIMARY KEY AUTOINCREMENT,note_id INTEGER NOT NULL REFERENCES notes(id) ON DELETE CASCADE,title TEXT NOT NULL,body TEXT NOT NULL,saved INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX versions_note ON versions(note_id,id)");
            db.execSQL("INSERT INTO notes VALUES(7,'Voyage','Liste',200,1,0),(9,'Corbeille avant','Texte gardé',100,0,1),(11,'','Sans titre',90,0,0)");
            db.execSQL("INSERT INTO versions VALUES(1,7,'Avant','Brouillon',100),(2,7,'Voyage','Liste',200),(3,9,'Corbeille avant','Texte gardé',100),(4,11,'','Sans titre',90)");db.setVersion(1);
        }
        store=new NoteStore(context);assertEquals(3,store.list("").size());assertEquals("Voyage\n\nListe",store.get(7).body());
        assertEquals("Avant\n\nBrouillon",store.history(7).get(1).body());assertEquals("Corbeille avant\n\nTexte gardé",store.get(9).body());assertEquals("Sans titre",store.get(11).body());
        assertEquals(200,store.get(7).updated());assertEquals(3,store.getReadableDatabase().getVersion());
        try(Cursor c=store.getReadableDatabase().rawQuery("PRAGMA foreign_key_check",null)){assertFalse(c.moveToFirst());}
        assertTrue(store.create("Nouvelle note")>11);store.remove(7);assertTrue(store.history(7).isEmpty());assertNotNull(store.get(9));
    }
    @Test public void invalidImportRollsBackAllInsertedNotes() throws Exception {
        store.create("Important");JSONObject backup=store.backup();JSONObject bad=new JSONObject(backup.getJSONArray("notes").getJSONObject(0).toString());bad.remove("body");backup.getJSONArray("notes").put(bad);
        try {store.importBackup(backup);fail("Invalid backup accepted");}catch(org.json.JSONException expected){}
        assertEquals(1,store.list("").size());assertEquals(1,store.history(store.list("").get(0).id()).size());
    }
    @Test public void reopeningDatabaseRetainsSavedContent() {
        long id=store.create("Sur disque");store.close();store=new NoteStore(RuntimeEnvironment.getApplication());assertEquals("Sur disque",store.get(id).body());
    }
    @Test public void typingSavesAutomaticallyAfterPause() throws Exception {
        long id=store.create("Avant");ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup();MainActivity activity=controller.get();edit(activity,id);
        ((EditText)activity.findViewById(102)).setText("Sauvegarde automatique");assertEquals("Avant",store.get(id).body());
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(651));
        assertEquals("Sauvegarde automatique",store.get(id).body());assertEquals(2,store.history(id).size());controller.pause().stop().destroy();
    }
    @Test public void pauseSavesDraftAndRecreationRestoresEditor() throws Exception {
        long id=store.create("Avant");ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup();MainActivity activity=controller.get();edit(activity,id);
        ((EditText)activity.findViewById(102)).setText("Après, sans attendre la sauvegarde");controller.pause();assertEquals("Après, sans attendre la sauvegarde",store.get(id).body());
        Bundle state=new Bundle();controller.saveInstanceState(state).stop().destroy();
        ActivityController<MainActivity> restored=Robolectric.buildActivity(MainActivity.class).create(state).start().restoreInstanceState(state).resume().visible();
        assertEquals("Après, sans attendre la sauvegarde",((EditText)restored.get().findViewById(102)).getText().toString());restored.pause().stop().destroy();
    }
    @Test @Config(sdk=26) public void appLaunchesOnAndroidEight() throws Exception {
        long id=store.create("Android 8");ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup();edit(controller.get(),id);assertNotNull(controller.get());controller.pause().stop().destroy();
    }
    @Test public void newNoteOpensKeyboardButReopeningEvenAnEmptyNoteDoesNot() {
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup();MainActivity activity=controller.get();
        View add=findText(activity.findViewById(android.R.id.content),"+  Nouvelle note");assertNotNull(add);add.performClick();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        EditText body=activity.findViewById(102);assertTrue(body.hasFocus());
        InputMethodManager keyboard=(InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        assertTrue(Shadows.shadowOf(keyboard).isSoftInputVisible());
        assertTrue(store.list("").isEmpty());body.setText("Une note à garder");
        activity.findViewById(R.id.save_note).performClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();long id=store.list("").get(0).id();
        activity.findViewById(android.R.id.content).findViewWithTag(id).performClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertFalse("Existing note must not focus the editor",activity.findViewById(102).hasFocus());
        assertFalse("Existing note must not display the keyboard",Shadows.shadowOf(keyboard).isSoftInputVisible());
        controller.pause().stop().destroy();
    }
    @Test public void rotatingAnActiveDraftKeepsWritingFocus() {
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup();MainActivity activity=controller.get();
        findText(activity.findViewById(android.R.id.content),"+  Nouvelle note").performClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        ((EditText)activity.findViewById(102)).setText("Brouillon en cours");Bundle state=new Bundle();controller.pause().saveInstanceState(state).stop().destroy();
        ActivityController<MainActivity> restored=Robolectric.buildActivity(MainActivity.class).create(state).start().restoreInstanceState(state).resume().visible();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();EditText body=restored.get().findViewById(102);
        assertEquals("Brouillon en cours",body.getText().toString());assertTrue(body.hasFocus());
        assertTrue(Shadows.shadowOf((InputMethodManager)restored.get().getSystemService(Context.INPUT_METHOD_SERVICE)).isSoftInputVisible());restored.pause().stop().destroy();
    }
    @Test public void leavingSelectedCardCancelsPendingDeleteDialog() {
        long id=store.create("À garder");ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup();MainActivity activity=controller.get();
        View card=activity.findViewById(android.R.id.content).findViewWithTag(id);card.performLongClick();assertTrue(card.isSelected());card.performClick();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200));
        assertFalse(card.isSelected());assertNull(ShadowAlertDialog.getLatestAlertDialog());assertNotNull(store.get(id));controller.pause().stop().destroy();
    }
    @Test public void menuSearchFiltersNotesSurvivesRotationAndCanBeCleared() throws Exception {
        long match=store.create("Voyage à préparer"),other=store.create("Idées de cuisine");
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup();MainActivity activity=controller.get();
        View content=activity.findViewById(android.R.id.content);
        assertNull(findText(content,"2 notes"));assertNull(findSearchInput(content));
        selectHomeMenu(activity,"Rechercher");AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
        EditText search=findSearchInput(dialog.getWindow().getDecorView());assertNotNull(search);search.setText("Voyage");
        search.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        content=activity.findViewById(android.R.id.content);assertNotNull(content.findViewWithTag(match));assertNull(content.findViewWithTag(other));
        Bundle state=new Bundle();controller.pause().saveInstanceState(state).stop().destroy();
        controller=Robolectric.buildActivity(MainActivity.class).create(state).start().restoreInstanceState(state).resume().visible();activity=controller.get();
        assertNull(activity.findViewById(android.R.id.content).findViewWithTag(other));
        selectHomeMenu(activity,"Rechercher");dialog=ShadowAlertDialog.getLatestAlertDialog();search=findSearchInput(dialog.getWindow().getDecorView());
        assertEquals("Voyage",search.getText().toString());search.setText("cuisine");dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        assertNotNull(activity.findViewById(android.R.id.content).findViewWithTag(match));
        selectHomeMenu(activity,"Afficher toutes les notes");content=activity.findViewById(android.R.id.content);
        assertNotNull(content.findViewWithTag(match));assertNotNull(content.findViewWithTag(other));assertNull(findSearchInput(content));
        selectHomeMenu(activity,"Rechercher");dialog=ShadowAlertDialog.getLatestAlertDialog();findSearchInput(dialog.getWindow().getDecorView()).setText("introuvable");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertNull(activity.findViewById(android.R.id.content).findViewWithTag(match));
        selectHomeMenu(activity,"Rechercher");dialog=ShadowAlertDialog.getLatestAlertDialog();findSearchInput(dialog.getWindow().getDecorView()).setText("  ");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertNotNull(activity.findViewById(android.R.id.content).findViewWithTag(other));
        controller.pause().stop().destroy();
    }
    private void selectHomeMenu(MainActivity activity,String title) {
        findText(activity.findViewById(android.R.id.content),"⋯").performClick();
        android.widget.PopupMenu menu=org.robolectric.shadows.ShadowPopupMenu.getLatestPopupMenu();
        for(int i=0;i<menu.getMenu().size();i++)if(title.contentEquals(menu.getMenu().getItem(i).getTitle())) {
            Shadows.shadowOf(menu).getOnMenuItemClickListener().onMenuItemClick(menu.getMenu().getItem(i));menu.dismiss();return;
        }
        fail("Missing menu action: "+title);
    }
    private EditText findSearchInput(View view) {
        if(view instanceof EditText)return (EditText)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            EditText found=findSearchInput(((ViewGroup)view).getChildAt(i));if(found!=null)return found;
        }
        return null;
    }
    private View findText(View view,String text) {
        if(view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=findText(group.getChildAt(i),text);if(found!=null)return found;}}return null;
    }
    @Test public void okSavesImmediatelyClosesNoteAndHidesKeyboard() {
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup();MainActivity activity=controller.get();
        findText(activity.findViewById(android.R.id.content),"+  Nouvelle note").performClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        ((EditText)activity.findViewById(102)).setText("Sauvegarde via OK");assertTrue(store.list("").isEmpty());
        assertNull(findText(activity.findViewById(android.R.id.content),"Historique"));activity.findViewById(R.id.save_note).performClick();
        assertEquals("Sauvegarde via OK",store.list("").get(0).body());assertNull(activity.findViewById(102));
        assertFalse(Shadows.shadowOf((InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).isSoftInputVisible());
        controller.pause().stop().destroy();
    }
    @Test public void whitespaceDraftNeverCreatesANoteEvenOnPauseAndOk() {
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup();MainActivity activity=controller.get();
        findText(activity.findViewById(android.R.id.content),"+  Nouvelle note").performClick();
        ((EditText)activity.findViewById(102)).setText(" \n\t\u00a0\u200b");Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(700));
        assertTrue(store.list("").isEmpty());controller.pause();assertTrue(store.list("").isEmpty());controller.resume();
        activity.findViewById(R.id.save_note).performClick();assertTrue(store.list("").isEmpty());assertNull(activity.findViewById(102));controller.pause().stop().destroy();
    }
    @Test public void okSitsAboveKeyboardInsetsOnModernAndroid() throws Exception {
        long id=store.create("Texte");ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup();MainActivity activity=controller.get();edit(activity,id);
        View root=((ViewGroup)activity.findViewById(android.R.id.content)).getChildAt(0);
        View ok=activity.findViewById(R.id.save_note);View saved=findText(root,"Enregistré sur cet appareil");
        assertFalse(ok.isShown());assertNull(saved);
        android.view.WindowInsets insets=new android.view.WindowInsets.Builder().setInsets(android.view.WindowInsets.Type.systemBars(),android.graphics.Insets.of(0,72,0,72)).setInsets(android.view.WindowInsets.Type.ime(),android.graphics.Insets.of(0,0,0,900)).setVisible(android.view.WindowInsets.Type.ime(),true).build();
        root.dispatchApplyWindowInsets(insets);root.measure(View.MeasureSpec.makeMeasureSpec(1179,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY));root.layout(0,0,1179,2400);
        int[] position=new int[2];ok.getLocationOnScreen(position);assertTrue(root.getPaddingBottom()>=900);assertTrue(position[1]+ok.getHeight()<=1500);assertTrue(ok.getHeight()>0);assertTrue(ok.isShown());assertNull(saved);assertEquals(2,((ViewGroup)ok.getParent()).getChildCount());
        root.dispatchApplyWindowInsets(new android.view.WindowInsets.Builder(insets).setInsets(android.view.WindowInsets.Type.ime(),android.graphics.Insets.NONE).setVisible(android.view.WindowInsets.Type.ime(),false).build());
        assertFalse(ok.isShown());assertNull(saved);
        root.dispatchApplyWindowInsets(insets);assertTrue(ok.isShown());controller.pause().stop().destroy();
    }
    @Test public void emptyWritesAreRejectedByStorage() {
        try{store.create(" \n");fail("Empty note saved");}catch(IllegalArgumentException expected){}
        long id=store.create("À garder");try{store.save(id,"\u00a0");fail("Empty version saved");}catch(IllegalArgumentException expected){}
        assertEquals("À garder",store.get(id).body());assertEquals(1,store.history(id).size());
    }
    @Test public void clearingAnExistingNoteKeepsItUntilOkThenRemovesIt() throws Exception {
        long id=store.create("Texte à remplacer");ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup();MainActivity activity=controller.get();edit(activity,id);
        ((EditText)activity.findViewById(102)).setText(" \n");Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(700));
        assertEquals("Texte à remplacer",store.get(id).body());activity.findViewById(R.id.save_note).performClick();
        assertNull(store.get(id));assertTrue(store.history(id).isEmpty());controller.pause().stop().destroy();
    }
    @Test public void upgradingVersionTwoCleansOnlyUselessEmptyNotes() {
        store.close();Context context=RuntimeEnvironment.getApplication();
        try(SQLiteDatabase db=context.openOrCreateDatabase("carnet.db",0,null)) {
            db.execSQL("CREATE TABLE notes(id INTEGER PRIMARY KEY AUTOINCREMENT,body TEXT NOT NULL,updated INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE versions(id INTEGER PRIMARY KEY AUTOINCREMENT,note_id INTEGER NOT NULL REFERENCES notes(id) ON DELETE CASCADE,body TEXT NOT NULL,saved INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX versions_note ON versions(note_id,id)");
            db.execSQL("INSERT INTO notes VALUES(1,'  ',100),(2,'',200)");db.execSQL("INSERT INTO versions VALUES(1,1,'',100),(2,2,'À récupérer',150),(3,2,'',200)");db.setVersion(2);
        }
        store=new NoteStore(context);assertNull(store.get(1));assertTrue(store.history(1).isEmpty());assertEquals("À récupérer",store.get(2).body());assertEquals(2,store.history(2).size());
    }
    @Test public void importingEmptyNotesSkipsThemButKeepsUsefulHistory() throws Exception {
        JSONObject backup=new JSONObject("{\"format\":\"carnet\",\"version\":2,\"notes\":[{\"body\":\" \",\"updated\":1,\"versions\":[]},{\"body\":\"\",\"updated\":3,\"versions\":[{\"body\":\"\",\"saved\":3},{\"body\":\"À garder\",\"saved\":2}]}]}");
        assertEquals(1,store.importBackup(backup));assertEquals("À garder",store.list("").get(0).body());assertEquals(2,store.history(store.list("").get(0).id()).size());
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers="fr-w393dp-h851dp-xxhdpi")
    public void renderActualScreensForVisualReview() throws Exception {
        store.create("Un coin lecture près de la fenêtre, quelques plantes et de la lumière.");
        store.create("Écouter le prochain album recommandé par les amis.");
        long id=store.create("Prendre le temps de ralentir.\n\nRéserver le petit gîte, préparer le sac et choisir une randonnée.\n\nNe pas oublier l’appareil photo et un carnet pour les idées en chemin.");
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup();MainActivity activity=controller.get();capture(activity,"notes");
        activity.findViewById(android.R.id.content).findViewWithTag(id).performLongClick();capture(activity,"selection");
        edit(activity,id);capture(activity,"editor");controller.pause().stop().destroy();
        RuntimeEnvironment.getApplication().getSharedPreferences("MainActivity",0).edit().putString("theme","dark").commit();
        controller=Robolectric.buildActivity(MainActivity.class).setup();capture(controller.get(),"dark");controller.pause().stop().destroy();
        for(NoteStore.Note note:store.list(""))store.remove(note.id());
        controller=Robolectric.buildActivity(MainActivity.class).setup();capture(controller.get(),"empty");controller.pause().stop().destroy();
    }
    private void capture(MainActivity activity,String name) throws Exception {
        View view=activity.findViewById(android.R.id.content);int width=1179,height=2400;
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height);
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888);view.draw(new android.graphics.Canvas(bitmap));
        java.io.File directory=new java.io.File("build/visual-check");directory.mkdirs();try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(directory,name+".png"))){assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}
    }
}
