package fr.nico.carnet;

import android.content.Context;
import android.os.Bundle;
import android.widget.EditText;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class CarnetTest {
    NoteStore store;
    @Before public void setup() { Context context=RuntimeEnvironment.getApplication(); context.deleteDatabase("carnet.db"); store=new NoteStore(context); }
    @After public void close() { store.close(); }
    @Test public void saveAndRestoreKeepEveryVersion() {
        long id=store.create("Voyage","Première idée");
        assertTrue(store.save(id,"Voyage","Deuxième idée"));
        assertFalse(store.save(id,"Voyage","Deuxième idée"));
        NoteStore.Version old=store.history(id).get(1); store.save(id,old.title(),old.body());
        assertEquals("Première idée",store.get(id).body());
        assertEquals(3,store.history(id).size());
        assertEquals("Deuxième idée",store.history(id).get(1).body());
    }
    @Test public void trashIsRecoverableAndDeleteCascadesOnlyForTrash() {
        long id=store.create("Liste","☐ Café"); store.remove(id); assertNotNull(store.get(id));
        store.flag(id,"deleted",true); assertTrue(store.list("",0).isEmpty()); assertEquals(1,store.list("",2).size());
        store.flag(id,"deleted",false); assertEquals("☐ Café",store.get(id).body());
        store.flag(id,"deleted",true); store.remove(id); assertNull(store.get(id)); assertTrue(store.history(id).isEmpty());
    }
    @Test public void searchTreatsSqlAndWildcardsAsTextAndFavoritesSortFirst() {
        long first=store.create("Budget 50%","a_b et ' OR 1=1 --"); store.create("Autre","Rien"); store.flag(first,"pinned",true);
        assertEquals(first,store.list("",0).get(0).id()); assertEquals(1,store.list("",1).size());
        assertEquals(1,store.list("%",0).size()); assertEquals(1,store.list("_",0).size()); assertTrue(store.list("x' OR 1=1 --",0).isEmpty());
    }
    @Test public void backupRestoresNotesTrashAndHistoryWithoutOverwriting() throws Exception {
        long id=store.create("Été ☀","éè日本語"); store.save(id,"Été ☀","Texte modifié"); store.flag(id,"pinned",true); store.flag(id,"deleted",true);
        JSONObject backup=store.backup(); assertEquals(1,store.importBackup(backup));
        assertEquals(2,store.list("",2).size()); long copy=store.list("",2).get(0).id(); assertNotEquals(id,copy);
        assertEquals(2,store.history(copy).size()); assertEquals("éè日本語",store.history(copy).get(1).body()); assertTrue(store.get(copy).pinned());
    }
    @Test public void invalidImportRollsBackAllInsertedNotes() throws Exception {
        store.create("Original","Important"); JSONObject backup=store.backup(); JSONObject bad=new JSONObject(backup.getJSONArray("notes").getJSONObject(0).toString()); bad.remove("body"); backup.getJSONArray("notes").put(bad);
        try {store.importBackup(backup); fail("Invalid backup accepted");}catch(org.json.JSONException expected){}
        assertEquals(1,store.list("",0).size()); assertEquals(1,store.history(store.list("",0).get(0).id()).size());
    }
    @Test public void reopeningDatabaseRetainsSavedContent() {
        long id=store.create("Persistant","Sur disque"); store.close(); store=new NoteStore(RuntimeEnvironment.getApplication()); assertEquals("Sur disque",store.get(id).body());
    }
    @Test public void typingSavesAutomaticallyAfterPause() throws Exception {
        long id=store.create("Titre","");ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup();MainActivity activity=controller.get();
        Method edit=MainActivity.class.getDeclaredMethod("showEditor",long.class);edit.setAccessible(true);edit.invoke(activity,id);
        ((EditText)activity.findViewById(102)).setText("Sauvegarde automatique");
        assertEquals("",store.get(id).body());
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(651));
        assertEquals("Sauvegarde automatique",store.get(id).body());assertEquals(2,store.history(id).size());controller.pause().stop().destroy();
    }
    @Test public void pauseSavesDraftAndRecreationRestoresEditor() throws Exception {
        long id=store.create("Titre","Avant"); ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup(); MainActivity activity=controller.get();
        Method edit=MainActivity.class.getDeclaredMethod("showEditor",long.class);edit.setAccessible(true);edit.invoke(activity,id);
        ((EditText)activity.findViewById(102)).setText("Après, sans attendre la sauvegarde"); controller.pause();
        assertEquals("Après, sans attendre la sauvegarde",store.get(id).body());
        Bundle state=new Bundle();controller.saveInstanceState(state).stop().destroy();
        ActivityController<MainActivity> restored=Robolectric.buildActivity(MainActivity.class).create(state).start().restoreInstanceState(state).resume().visible();
        assertEquals("Après, sans attendre la sauvegarde",((EditText)restored.get().findViewById(102)).getText().toString()); restored.pause().stop().destroy();
    }
    @Test @Config(sdk=26) public void appLaunchesOnAndroidEight() {
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup(); assertNotNull(controller.get());controller.pause().stop().destroy();
    }
    @Test public void checklistTogglesAndPersists() throws Exception {
        long id=store.create("Courses","Pain\nLait"); ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup();MainActivity activity=controller.get();
        Method edit=MainActivity.class.getDeclaredMethod("showEditor",long.class);edit.setAccessible(true);edit.invoke(activity,id);
        EditText body=activity.findViewById(102);body.setSelection(0);Method toggle=MainActivity.class.getDeclaredMethod("toggleChecklist");toggle.setAccessible(true);toggle.invoke(activity);assertEquals("☐ Pain\nLait",body.getText().toString());toggle.invoke(activity);assertEquals("☑ Pain\nLait",body.getText().toString());controller.pause().stop().destroy();assertEquals("☑ Pain\nLait",store.get(id).body());
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers="fr-w393dp-h851dp-xxhdpi")
    public void renderActualScreensForVisualReview() throws Exception {
        long id=store.create("Un week-end au vert", "Prendre le temps de ralentir.\n\n☑ Réserver le petit gîte\n☐ Préparer le sac\n☐ Choisir une randonnée\n\nNe pas oublier l’appareil photo et un carnet pour les idées en chemin.");
        store.flag(id,"pinned",true);store.create("Une idée à garder", "Un coin lecture près de la fenêtre, quelques plantes et de la lumière.");
        store.create("À écouter", "Le prochain album recommandé par les amis.");
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup(); MainActivity activity=controller.get();
        capture(activity,"notes");
        Method edit=MainActivity.class.getDeclaredMethod("showEditor",long.class);edit.setAccessible(true);edit.invoke(activity,id);capture(activity,"editor");controller.pause().stop().destroy();
        RuntimeEnvironment.getApplication().getSharedPreferences("MainActivity",0).edit().putString("theme","dark").commit();
        controller=Robolectric.buildActivity(MainActivity.class).setup();capture(controller.get(),"dark");controller.pause().stop().destroy();
    }
    private void capture(MainActivity activity,String name) throws Exception {
        android.view.View view=activity.findViewById(android.R.id.content);
        int width=1179,height=2400;
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(width,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(height,android.view.View.MeasureSpec.EXACTLY));view.layout(0,0,width,height);
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888);view.draw(new android.graphics.Canvas(bitmap));
        java.io.File directory=new java.io.File("build/visual-check");directory.mkdirs();try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(directory,name+".png"))){assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}
    }
}
