package fr.nico.carnet;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.ProgressBar;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** User-triggered GitHub updates. The worker survives Activity rotation, never reads notes. */
public final class UpdateManager {
    static final String REPOSITORY="nicolasght/carnet-android";
    static final String LATEST="https://api.github.com/repos/"+REPOSITORY+"/releases/latest";
    static final long MAX_APK=64L*1024*1024;
    private static UpdateManager instance;
    private final Context app;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private WeakReference<Activity> host=new WeakReference<>(null);
    private AlertDialog dialog;
    private String state="idle",error="";
    private boolean opened,waitingPermission;
    private Release release;
    record Release(String tag,String version,String url,String hash,long size) {
        String mirror() { return "https://raw.githubusercontent.com/"+REPOSITORY+"/"+tag+"/downloads/Carnet.apk"; }
    }
    private UpdateManager(Context context) { app=context.getApplicationContext(); }
    static synchronized UpdateManager get(Context context) {
        if(instance==null)instance=new UpdateManager(context);return instance;
    }
    void attach(Activity activity) {
        host=new WeakReference<>(activity);
        if(waitingPermission) {
            waitingPermission=false;
            if(app.getPackageManager().canRequestPackageInstalls()) { install();return; }
            opened=true;
        }
        render();
    }
    void detach(Activity activity) {
        if(host.get()==activity) {host.clear();if(dialog!=null){dialog.dismiss();dialog=null;}}
    }
    private Activity activity() { Activity a=host.get();return a==null||a.isDestroyed()||a.isFinishing()?null:a; }
    void show() {
        opened=true;
        if(state.equals("checking")||state.equals("downloading")||state.equals("ready")) {render();return;}
        state="checking";render();
        worker.execute(()->{
            try {
                JSONObject json=new JSONObject(new String(fetch(LATEST,1024*1024),StandardCharsets.UTF_8));
                Release found=parseRelease(json);String installed=app.getPackageManager().getPackageInfo(app.getPackageName(),0).versionName;
                main.post(()->{release=found;state=compareVersions(found.version,installed)>0?"available":"current";render();});
            } catch(Exception e) { fail("Impossible de vérifier GitHub. Vérifiez votre connexion et réessayez."); }
        });
    }
    static int compareVersions(String left,String right) {
        String[] a=versionParts(left),b=versionParts(right);
        for(int i=0;i<3;i++) {int comparison=Integer.compare(Integer.parseInt(a[i]),Integer.parseInt(b[i]));if(comparison!=0)return comparison;}
        return 0;
    }
    private static String[] versionParts(String version) {
        if(version==null||!version.matches("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}"))throw new IllegalArgumentException("Version invalide");
        return version.split("\\.");
    }
    static Release parseRelease(JSONObject json) throws Exception {
        if(json.optBoolean("draft",true)||json.optBoolean("prerelease",true))throw new IOException("Version non stable");
        String tag=json.getString("tag_name");if(!tag.startsWith("v"))throw new IOException("Tag invalide");
        String version=tag.substring(1);versionParts(version);
        String expected="https://github.com/"+REPOSITORY+"/releases/download/"+tag+"/Carnet.apk";
        JSONArray assets=json.getJSONArray("assets");
        for(int i=0;i<assets.length();i++) {
            JSONObject asset=assets.getJSONObject(i);if(!"Carnet.apk".equals(asset.optString("name")))continue;
            String url=asset.getString("browser_download_url"),digest=asset.getString("digest");long size=asset.getLong("size");
            if(!expected.equals(url)||!digest.matches("sha256:[a-fA-F0-9]{64}")||size<=0||size>MAX_APK)throw new IOException("Fichier de mise à jour invalide");
            return new Release(tag,version,url,digest.substring(7).toLowerCase(Locale.ROOT),size);
        }
        throw new IOException("APK absent");
    }
    private void render() {
        Activity a=activity();if(a==null||!opened)return;
        if(dialog!=null)dialog.dismiss();
        AlertDialog.Builder builder=new AlertDialog.Builder(a).setTitle("Mises à jour");
        switch(state) {
            case "checking": builder.setMessage("Recherche sur GitHub…").setView(new ProgressBar(a));break;
            case "downloading": builder.setMessage("Téléchargement et vérification de Carnet "+release.version+"…").setView(new ProgressBar(a));break;
            case "available": builder.setMessage("Carnet "+release.version+" est disponible. Vos notes et leur historique seront conservés.").setPositiveButton("Mettre à jour",(d,w)->download());break;
            case "ready": builder.setMessage("Carnet "+release.version+" est prêt. Android vous demandera de confirmer l’installation.").setPositiveButton("Installer",(d,w)->install());break;
            case "current": builder.setMessage("Carnet est à jour.");break;
            case "error": builder.setMessage(error).setPositiveButton("Réessayer",(d,w)->show());break;
            default:return;
        }
        builder.setNegativeButton(state.equals("checking")||state.equals("downloading")?"Continuer à écrire":"Fermer",(d,w)->opened=false);
        builder.setOnCancelListener(d->opened=false);dialog=builder.create();dialog.show();
    }
    private void download() {
        state="downloading";render();Release target=release;
        worker.execute(()->{
            File dir=new File(app.getCacheDir(),"updates"),partial=new File(dir,"Carnet.part"),ready=new File(dir,"Carnet.apk");
            try {
                if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Stockage indisponible");
                try { downloadFile(target.url,partial,target); }
                catch(IOException first) { downloadFile(target.mirror(),partial,target); }
                verifyArchive(app,partial,target);
                if(ready.exists()&&!ready.delete())throw new IOException("Ancien téléchargement occupé");
                if(!partial.renameTo(ready))throw new IOException("Impossible de préparer l’APK");
                main.post(()->{state="ready";if(!opened)Toast.makeText(app,"Mise à jour prête dans le menu de Carnet",Toast.LENGTH_LONG).show();render();});
            } catch(Exception e) {
                partial.delete();fail("La mise à jour n’a pas pu être téléchargée ou vérifiée. Réessayez avec une connexion disponible et de l’espace libre. Vos notes sont conservées.");
            }
        });
    }
    static void verifyArchive(Context context,File file,Release release) throws Exception {
        PackageManager pm=context.getPackageManager();
        int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
        PackageInfo next=pm.getPackageArchiveInfo(file.getAbsolutePath(),flags),current=pm.getPackageInfo(context.getPackageName(),flags);
        if(next==null||!context.getPackageName().equals(next.packageName)||!release.version.equals(next.versionName)||versionCode(next)<=versionCode(current))throw new IOException("APK incompatible");
        Signature[] oldSigners=signers(current),newSigners=signers(next);
        if(oldSigners==null||newSigners==null||oldSigners.length==0||!Arrays.equals(oldSigners,newSigners))throw new IOException("Signature incompatible");
    }
    private static long versionCode(PackageInfo info) {return Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode;}
    private static Signature[] signers(PackageInfo info) {
        return Build.VERSION.SDK_INT>=28?(info.signingInfo==null?null:info.signingInfo.getApkContentsSigners()):info.signatures;
    }
    private void install() {
        Activity a=activity();if(a==null)return;
        try {
            File ready=new File(app.getCacheDir(),"updates/Carnet.apk");verifyArchive(app,ready,release);
            if(!app.getPackageManager().canRequestPackageInstalls()) {
                new AlertDialog.Builder(a).setTitle("Autoriser la mise à jour")
                    .setMessage("Android demande d’autoriser Carnet à installer ses mises à jour. Activez cette autorisation, puis revenez à Carnet.")
                    .setNegativeButton("Annuler",null).setPositiveButton("Ouvrir les paramètres",(d,w)->{
                        try {waitingPermission=true;opened=false;a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+app.getPackageName())));}
                        catch(Exception e){waitingPermission=false;fail("Les paramètres d’installation ne sont pas accessibles sur cet appareil.");}
                    }).show();return;
            }
            Uri uri=Uri.parse("content://"+app.getPackageName()+".updates/Carnet.apk");
            Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(intent);opened=false;
        }catch(Exception e){fail("Impossible de lancer l’installation. Recherchez à nouveau la mise à jour.");}
    }
    private void fail(String message) {main.post(()->{error=message;state="error";if(!opened)Toast.makeText(app,message,Toast.LENGTH_LONG).show();render();});}
    static boolean allowedUrl(String address) {
        try {
            URL url=new URL(address);String host=url.getHost();
            return url.getProtocol().equals("https")&&url.getUserInfo()==null&&(url.getPort()==-1||url.getPort()==443)&&
                (host.equals("api.github.com")||host.equals("github.com")||host.equals("raw.githubusercontent.com")||host.equals("release-assets.githubusercontent.com")||host.equals("objects.githubusercontent.com"));
        }catch(Exception e){return false;}
    }
    private static HttpURLConnection connect(String address) throws IOException {
        for(int redirects=0;redirects<6;redirects++) {
            if(!allowedUrl(address))throw new IOException("Adresse de téléchargement refusée");
            HttpURLConnection connection=(HttpURLConnection)new URL(address).openConnection();
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(10000);connection.setReadTimeout(20000);
            connection.setRequestProperty("User-Agent","Carnet-Android");connection.setRequestProperty("Accept","application/vnd.github+json, application/octet-stream");
            int code;
            try {code=connection.getResponseCode();}catch(IOException e){connection.disconnect();throw e;}
            if(code>=300&&code<=399) {String location=connection.getHeaderField("Location");connection.disconnect();if(location==null)throw new IOException("Redirection invalide");address=new URL(new URL(address),location).toString();continue;}
            if(code!=200){connection.disconnect();throw new IOException("HTTP "+code);}return connection;
        }
        throw new IOException("Trop de redirections");
    }
    static byte[] fetch(String url,int limit) throws IOException {
        HttpURLConnection connection=connect(url);
        try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192];int read;
            while((read=in.read(buffer))!=-1){if(out.size()+read>limit)throw new IOException("Réponse trop volumineuse");out.write(buffer,0,read);}return out.toByteArray();
        } finally {connection.disconnect();}
    }
    static void downloadFile(String url,File file,Release release) throws IOException {
        HttpURLConnection connection=connect(url);
        try(InputStream in=connection.getInputStream();OutputStream out=new FileOutputStream(file)) {copyVerified(in,out,release);}
        finally {connection.disconnect();}
    }
    static void copyVerified(InputStream in,OutputStream out,Release release) throws IOException {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[8192];int read;long total=0;
            while((read=in.read(buffer))!=-1){total+=read;if(total>release.size||total>MAX_APK)throw new IOException("Taille invalide");digest.update(buffer,0,read);out.write(buffer,0,read);}
            StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(Locale.ROOT,"%02x",b&255));
            if(total!=release.size||!hash.toString().equals(release.hash))throw new IOException("Téléchargement incomplet ou altéré");
        }catch(java.security.NoSuchAlgorithmException e){throw new IOException(e);}
    }
}
