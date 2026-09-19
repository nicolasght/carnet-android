package fr.nico.carnet;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private NoteStore store;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService files = Executors.newSingleThreadExecutor();
    private final Runnable autosave = () -> save();
    private long current = -1;
    private int filter;
    private String query = "";
    private EditText title, body;
    private TextView status;
    private LinearLayout root, cards;
    private boolean dark, dirty, loading;
    private int bg, paper, ink, muted, accent, soft;
    private static final int EXPORT = 10, IMPORT = 11;
    private static final int TITLE_ID = 101, BODY_ID = 102;

    @Override public void onCreate(Bundle state) {
        String mode = getPreferences(0).getString("theme", "system");
        dark = mode.equals("dark") || (mode.equals("system") && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        setTheme(dark ? R.style.AppThemeDark : R.style.AppTheme);
        super.onCreate(state);
        bg = color(dark ? "#171820" : "#F4F5FA"); paper = color(dark ? "#242530" : "#FFFFFF");
        ink = color(dark ? "#F1F0F8" : "#242236"); muted = color(dark ? "#B6B3C8" : "#696579");
        accent = color(dark ? "#C5BCFF" : "#5B50B5"); soft = color(dark ? "#383347" : "#EAE6FC");
        store = new NoteStore(this);
        if (state != null) { current = state.getLong("note", -1); filter = state.getInt("filter"); query = state.getString("query", ""); }
        if (current >= 0 && store.get(current) != null) {
            showEditor(current);
            if (state != null && state.containsKey("draftTitle")) {
                title.setText(state.getString("draftTitle")); body.setText(state.getString("draftBody"));
            }
        } else showHome();
    }
    private int color(String value) { return Color.parseColor(value); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private GradientDrawable shape(int fill, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(fill); d.setCornerRadius(dp(radius)); return d; }
    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setFontFeatureSettings("kern"); return t;
    }
    private TextView heading(String value, int size) { TextView t = text(value, size, ink); t.setTypeface(Typeface.create("sans-serif-rounded", Typeface.BOLD)); return t; }
    private Button button(String value, Runnable action) {
        Button b = new Button(this); b.setText(value); b.setAllCaps(false); b.setTextSize(14); b.setTextColor(accent);
        b.setMinHeight(dp(48)); b.setMinimumHeight(dp(48)); b.setPadding(dp(12), dp(6), dp(12), dp(6));
        b.setBackground(shape(soft, 14)); b.setOnClickListener(v -> action.run()); return b;
    }
    private void gap(LinearLayout parent, int height) { View v = new View(this); parent.addView(v, new LinearLayout.LayoutParams(1, dp(height))); }
    private void screen() {
        root = column(); root.setPadding(dp(20), dp(12), dp(20), dp(12)); root.setBackgroundColor(bg);
        root.setFitsSystemWindows(true);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(dp(20) + insets.getSystemWindowInsetLeft(), dp(12) + insets.getSystemWindowInsetTop(), dp(20) + insets.getSystemWindowInsetRight(), dp(12) + insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        getWindow().setStatusBarColor(bg); getWindow().setNavigationBarColor(bg);
        getWindow().getDecorView().setSystemUiVisibility(dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        setContentView(root);
    }
    private void hideKeyboard() {
        View focused = getCurrentFocus();
        if (focused != null) ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focused.getWindowToken(), 0);
    }
    private void showHome() {
        hideKeyboard(); current = -1; title = null; body = null; dirty = false; screen();
        LinearLayout header = row(); header.addView(heading("Carnet", 34), new LinearLayout.LayoutParams(0, -2, 1));
        Button menu = button("⋯", () -> {}); menu.setContentDescription("Options et sauvegardes"); menu.setOnClickListener(v -> homeMenu(menu)); header.addView(menu, new LinearLayout.LayoutParams(dp(52), dp(48))); root.addView(header);
        root.addView(text("Une idée, une note. Rien de perdu.", 14, muted)); gap(root, 20);
        EditText search = new EditText(this); search.setSingleLine(); search.setTextSize(16); search.setTextColor(ink); search.setHintTextColor(muted);
        search.setHint("Rechercher dans les notes"); search.setContentDescription("Rechercher dans les notes"); search.setPadding(dp(16), dp(10), dp(16), dp(10)); search.setBackground(shape(paper, 16)); search.setText(query);
        root.addView(search, new LinearLayout.LayoutParams(-1, dp(52))); gap(root, 12);
        LinearLayout tabs = row(); String[] names = {"Notes", "Favoris", "Corbeille"};
        for(int i=0;i<3;i++) {
            final int index=i; Button tab=button(names[i], () -> { filter=index; showHome(); });
            tab.setTextColor(filter == i ? (dark ? ink : Color.WHITE) : muted); tab.setBackground(shape(filter == i ? (dark ? color("#605292") : accent) : bg, 14));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1); p.setMargins(dp(2), 0, dp(2), 0); tabs.addView(tab,p);
        }
        root.addView(tabs); gap(root, 12);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); cards=column(); scroll.addView(cards); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        gap(root,12); if(filter != 2) { Button add=button("+  Nouvelle note", () -> guarded(() -> showEditor(store.create("", "")))); add.setTextColor(dark ? bg : Color.WHITE); add.setTextSize(17); add.setBackground(shape(accent,18)); root.addView(add,new LinearLayout.LayoutParams(-1,dp(56))); }
        search.addTextChangedListener(watcher(() -> { query=search.getText().toString(); refreshCards(); }));
        root.setFocusableInTouchMode(true); root.requestFocus(); refreshCards();
    }
    private void refreshCards() {
        cards.removeAllViews();
        List<NoteStore.Note> notes=store.list(query,filter);
        TextView count=text(notes.size()+ (notes.size()==1 ? " note" : " notes"),12,muted); count.setPadding(dp(3),0,0,dp(10)); cards.addView(count);
        if(notes.isEmpty()) {
            LinearLayout empty=column(); empty.setGravity(Gravity.CENTER); empty.setPadding(dp(18),dp(54),dp(18),dp(32));
            TextView symbol=text(filter==2 ? "↶" : "✎",48,accent); empty.addView(symbol); gap(empty,16);
            TextView h=heading(!query.isEmpty()?"Aucune note trouvée":filter==2?"La corbeille est vide":filter==1?"Vos notes importantes, ici":"Votre première idée ?",23); h.setGravity(Gravity.CENTER); empty.addView(h); gap(empty,10);
            TextView desc=text(!query.isEmpty()?"Essayez un autre mot.":filter==2?"Les notes supprimées restent ici jusqu’à leur suppression définitive.":filter==1?"Ouvrez une note et ajoutez-la aux favoris.":"Touchez « Nouvelle note ».\nTout s’enregistre automatiquement.",15,muted); desc.setGravity(Gravity.CENTER); empty.addView(desc); cards.addView(empty); return;
        }
        for(NoteStore.Note note:notes) {
            LinearLayout card=column(); card.setPadding(dp(18),dp(16),dp(18),dp(16)); card.setBackground(shape(paper,18));
            TextView name=heading((note.pinned()?"★  ":"")+note.label(),20); name.setMaxLines(2); name.setEllipsize(android.text.TextUtils.TruncateAt.END); card.addView(name); gap(card,7);
            TextView preview=text(note.body().isEmpty()?"Note vide":note.body(),15,muted); preview.setMaxLines(3); preview.setEllipsize(android.text.TextUtils.TruncateAt.END); preview.setLineSpacing(dp(3),1); card.addView(preview); gap(card,14);
            card.addView(text(date(note.updated()),12,muted)); card.setClickable(true); card.setFocusable(true); card.setOnClickListener(v->showEditor(note.id()));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.bottomMargin=dp(10); cards.addView(card,p);
        }
    }
    private String date(long time) { return DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(new Date(time)); }
    private void showEditor(long id) {
        handler.removeCallbacks(autosave); current=id; dirty=false; loading=true;
        NoteStore.Note note=store.get(id); if(note==null) { showHome(); return; } screen();
        LinearLayout top=row(); Button back=button("‹", () -> { if(save()) showHome(); }); back.setContentDescription("Revenir aux notes"); top.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));
        TextView caption=text(note.deleted()?"Dans la corbeille":"MA NOTE",12,muted); caption.setPadding(dp(14),0,0,0); top.addView(caption,new LinearLayout.LayoutParams(0,-2,1));
        Button more=button("⋯",()->{}); more.setContentDescription("Actions sur la note"); more.setOnClickListener(v->noteMenu(more)); top.addView(more,new LinearLayout.LayoutParams(dp(48),dp(48))); root.addView(top); gap(root,12);
        LinearLayout sheet=column(); sheet.setPadding(dp(16),dp(12),dp(16),dp(12)); sheet.setBackground(shape(paper,20));
        title=new EditText(this); title.setId(TITLE_ID); title.setSaveEnabled(false); title.setText(note.title()); title.setHint("Titre de la note"); title.setTextSize(27); title.setTypeface(Typeface.create("sans-serif-rounded",Typeface.BOLD)); title.setTextColor(ink); title.setHintTextColor(muted); title.setBackgroundColor(Color.TRANSPARENT); title.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES); title.setMaxLines(3); sheet.addView(title,new LinearLayout.LayoutParams(-1,-2));
        body=new EditText(this); body.setId(BODY_ID); body.setSaveEnabled(false); body.setGravity(Gravity.TOP); body.setText(note.body()); body.setHint("Écrivez ce qui vous passe par la tête…"); body.setTextSize(18); body.setTextColor(ink); body.setHintTextColor(muted); body.setBackgroundColor(Color.TRANSPARENT); body.setLineSpacing(dp(5),1); body.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES); body.setPadding(dp(4),dp(12),dp(4),dp(12)); sheet.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        root.addView(sheet,new LinearLayout.LayoutParams(-1,0,1)); gap(root,10);
        status=text(note.deleted()?"Restaurez cette note pour la modifier.":"Enregistré sur cet appareil",12,muted); root.addView(status); gap(root,8);
        LinearLayout bottom=row(); Button history=button("Historique",()->{if(save()) showHistory();}); bottom.addView(history,new LinearLayout.LayoutParams(0,dp(48),1));
        Button secondary=button(note.deleted()?"Restaurer":"☐  Liste",()->{
            if(note.deleted()) { guarded(()->{store.flag(current,"deleted",false); showEditor(current); toast("Note restaurée");}); }
            else toggleChecklist();
        }); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(48),1); p.leftMargin=dp(10); bottom.addView(secondary,p); root.addView(bottom);
        title.setEnabled(!note.deleted()); body.setEnabled(!note.deleted());
        TextWatcher changes=watcher(()-> { if(!loading) { dirty=true; status.setText("Enregistrement…"); handler.removeCallbacks(autosave); handler.postDelayed(autosave,650); } }); title.addTextChangedListener(changes); body.addTextChangedListener(changes);
        loading=false; root.setFocusableInTouchMode(true); root.requestFocus();
    }
    private TextWatcher watcher(Runnable action) { return new TextWatcher() { public void beforeTextChanged(CharSequence s,int st,int c,int a) {} public void onTextChanged(CharSequence s,int st,int before,int count) {} public void afterTextChanged(Editable e) { action.run(); } }; }
    private boolean save() {
        handler.removeCallbacks(autosave);
        if(current<0||title==null||!dirty) return true;
        try {
            store.save(current,title.getText().toString(),body.getText().toString()); dirty=false;
            status.setText("Enregistré · "+body.length()+" caractères"); return true;
        } catch(Exception e) { status.setText("Échec de sauvegarde. Gardez cette note ouverte."); toast("Enregistrement impossible : vérifiez l’espace disponible."); return false; }
    }
    private void toggleChecklist() {
        int pos=Math.max(0,body.getSelectionStart()); String value=body.getText().toString();
        int start=pos==0?0:value.lastIndexOf('\n',pos-1)+1;
        String prefix=value.substring(start); int replace=prefix.startsWith("☐ ")||prefix.startsWith("☑ ")?2:0;
        String next=prefix.startsWith("☐ ")?"☑ ":"☐ ";
        body.getText().replace(start,start+replace,next); body.requestFocus(); body.setSelection(Math.min(body.length(),pos+next.length()-replace));
    }
    private void noteMenu(View anchor) {
        if(!save()) return; NoteStore.Note note=store.get(current); PopupMenu menu=new PopupMenu(this,anchor);
        if(!note.deleted()) { menu.getMenu().add(note.pinned()?"Retirer des favoris":"Ajouter aux favoris"); menu.getMenu().add("Partager le texte"); menu.getMenu().add("Dupliquer"); menu.getMenu().add("Mettre à la corbeille"); }
        else menu.getMenu().add("Supprimer définitivement");
        menu.setOnMenuItemClickListener(item->{ String action=item.getTitle().toString(); guarded(()->{
            if(action.contains("favoris")) {store.flag(current,"pinned",!note.pinned()); toast(note.pinned()?"Favori retiré":"Ajouté aux favoris");}
            else if(action.equals("Partager le texte")) {Intent intent=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,note.label()+"\n\n"+note.body()); startActivity(Intent.createChooser(intent,"Partager la note"));}
            else if(action.equals("Dupliquer")) {showEditor(store.create(note.label()+" (copie)",note.body())); toast("Note dupliquée");}
            else if(action.equals("Mettre à la corbeille")) {store.flag(current,"deleted",true); showHome(); toast("Note placée dans la corbeille");}
            else new AlertDialog.Builder(this).setTitle("Supprimer définitivement ?").setMessage("Cette note et toutes ses versions seront effacées. Cette action est irréversible.").setNegativeButton("Annuler",null).setPositiveButton("Supprimer",(d,w)->guarded(()->{store.remove(current);showHome();})).show();
        }); return true;}); menu.show();
    }
    private void showHistory() {
        List<NoteStore.Version> versions=store.history(current); String[] labels=new String[versions.size()];
        for(int i=0;i<labels.length;i++) { NoteStore.Version v=versions.get(i); labels[i]=(i==0?"Version actuelle · ":"")+date(v.saved())+"\n"+(v.title().isEmpty()?"Sans titre":v.title())+" · "+v.body().length()+" caractères"; }
        new AlertDialog.Builder(this).setTitle("Historique · "+labels.length+" versions").setItems(labels,(dialog,which)->{
            NoteStore.Version version=versions.get(which); ScrollView scroll=new ScrollView(this); TextView preview=text(version.title()+"\n\n"+version.body(),17,ink); preview.setTextIsSelectable(true); preview.setPadding(dp(22),dp(18),dp(22),dp(18)); scroll.addView(preview);
            AlertDialog.Builder detail=new AlertDialog.Builder(this).setTitle(date(version.saved())).setView(scroll).setNegativeButton("Fermer",null);
            if(which!=0 && !store.get(current).deleted()) detail.setPositiveButton("Restaurer",(d,w)->new AlertDialog.Builder(this).setTitle("Restaurer cette version ?").setMessage("La version actuelle reste disponible dans l’historique.").setNegativeButton("Annuler",null).setPositiveButton("Restaurer",(a,b)->guarded(()->{store.save(current,version.title(),version.body());showEditor(current);toast("Version restaurée");})).show());
            detail.show();
        }).setNegativeButton("Fermer",null).show();
    }
    private void homeMenu(View anchor) {
        PopupMenu menu=new PopupMenu(this,anchor); menu.getMenu().add("Exporter une sauvegarde"); menu.getMenu().add("Importer une sauvegarde"); menu.getMenu().add("Apparence"); menu.getMenu().add("À propos");
        menu.setOnMenuItemClickListener(item->{switch(item.getTitle().toString()) {
            case "Exporter une sauvegarde": guarded(()->startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,"carnet-"+new java.text.SimpleDateFormat("yyyy-MM-dd-HHmm",java.util.Locale.ROOT).format(new Date())+".json"),EXPORT)); break;
            case "Importer une sauvegarde": new AlertDialog.Builder(this).setTitle("Importer une sauvegarde").setMessage("Les notes et leur historique seront ajoutés comme nouvelles copies. Vos notes actuelles restent intactes.").setNegativeButton("Annuler",null).setPositiveButton("Choisir un fichier",(d,w)->guarded(()->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),IMPORT))).show(); break;
            case "Apparence": String[] modes={"system","light","dark"}; String currentMode=getPreferences(0).getString("theme","system"); int selected=java.util.Arrays.asList(modes).indexOf(currentMode); new AlertDialog.Builder(this).setTitle("Apparence").setSingleChoiceItems(new String[]{"Selon le téléphone","Clair","Sombre"},selected,(d,w)->{getPreferences(0).edit().putString("theme",modes[w]).apply(); d.dismiss(); recreate();}).setNegativeButton("Fermer",null).show(); break;
            default: new AlertDialog.Builder(this).setTitle("Carnet 1.0").setMessage("Votre bloc-notes, tout simplement.\n\n• Sauvegarde après une pause de frappe et à la fermeture.\n• Historique conservé sans limite automatique.\n• Corbeille récupérable.\n• Aucune publicité, aucun compte, aucun accès réseau.\n\nLes notes restent sur cet appareil. Exportez une sauvegarde régulièrement et avant de désinstaller. Le fichier exporté contient vos notes en clair : gardez-le dans un endroit sûr.").setPositiveButton("Compris",null).show();
        } return true;}); menu.show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data); if(result!=RESULT_OK||data==null||data.getData()==null) return;
        android.net.Uri uri=data.getData(); if(request!=EXPORT&&request!=IMPORT)return;
        toast(request==EXPORT?"Export en cours…":"Import en cours…");
        // Use a separate helper so an Activity rotation cannot close an ongoing file operation.
        files.execute(()->{try(NoteStore transfer=new NoteStore(getApplicationContext())) {
            if(request==EXPORT) {
                byte[] bytes=transfer.backup().toString(2).getBytes(StandardCharsets.UTF_8);
                try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")) {if(out==null)throw new IOException();out.write(bytes);}
                handler.post(()->toast("Sauvegarde exportée avec l’historique"));
            } else {
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                try(InputStream in=getContentResolver().openInputStream(uri)) {
                    if(in==null)throw new IOException();byte[] buffer=new byte[8192];int read;
                    while((read=in.read(buffer))!=-1) {if(bytes.size()+read>16*1024*1024)throw new IOException("Le fichier dépasse 16 Mo");bytes.write(buffer,0,read);}
                }
                int count=transfer.importBackup(new JSONObject(bytes.toString(StandardCharsets.UTF_8.name())));
                handler.post(()->{toast(count+" notes importées"); if(!isDestroyed()&&current<0)refreshCards();});
            }
        }catch(Exception e){handler.post(()->{if(isDestroyed()||isFinishing()){toast(request==EXPORT?"Échec de l’export":"Échec de l’import : sauvegarde invalide");return;}new AlertDialog.Builder(this).setTitle(request==EXPORT?"Export impossible":"Import impossible").setMessage(request==EXPORT?"Vérifiez l’emplacement choisi et l’espace disponible.":"Choisissez une sauvegarde JSON de Carnet valide, de 16 Mo maximum. Aucune note existante n’a été modifiée.").setPositiveButton("Fermer",null).show();});}});
    }
    private void guarded(Runnable action) {try{action.run();}catch(Exception e){new AlertDialog.Builder(this).setTitle("Opération impossible").setMessage("Vérifiez l’espace disponible, puis réessayez. Vos notes existantes sont conservées.").setPositiveButton("Fermer",null).show();}}
    private void toast(String message){Toast.makeText(getApplicationContext(),message,Toast.LENGTH_LONG).show();}
    @Override public void onBackPressed(){if(current>=0){if(save())showHome();}else super.onBackPressed();}
    @Override protected void onPause(){save();super.onPause();}
    @Override protected void onSaveInstanceState(Bundle out){save();out.putLong("note",current);out.putInt("filter",filter);out.putString("query",query);if(current>=0&&title!=null){out.putString("draftTitle",title.getText().toString());out.putString("draftBody",body.getText().toString());}super.onSaveInstanceState(out);}
    @Override protected void onDestroy(){handler.removeCallbacks(autosave);files.shutdown();store.close();super.onDestroy();}
}
