package fr.nico.carnet;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class UpdateTest {
    private static final String HASH="ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private JSONObject release() throws Exception {
        JSONObject asset=new JSONObject().put("name","Carnet.apk").put("size",3).put("digest","sha256:"+HASH).put("browser_download_url","https://github.com/nicolasght/carnet-android/releases/download/v1.4.0/Carnet.apk");
        return new JSONObject().put("tag_name","v1.4.0").put("draft",false).put("prerelease",false).put("assets",new JSONArray().put(asset));
    }
    @Test public void versionsAreComparedNumerically() {
        assertTrue(UpdateManager.compareVersions("1.10.0","1.9.0")>0);assertEquals(0,UpdateManager.compareVersions("1.3.0","1.3.0"));assertTrue(UpdateManager.compareVersions("1.2.0","1.3.0")<0);
        try{UpdateManager.compareVersions("1.4.0-beta","1.3.0");fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void onlyStableReleaseWithExactAssetAndDigestIsAccepted() throws Exception {
        JSONObject json=release();UpdateManager.Release parsed=UpdateManager.parseRelease(json);
        assertEquals("1.4.0",parsed.version());assertEquals(HASH,parsed.hash());assertTrue(parsed.mirror().endsWith("/v1.4.0/downloads/Carnet.apk"));
        json.put("prerelease",true);rejectRelease(json);json.put("prerelease",false);
        JSONObject asset=json.getJSONArray("assets").getJSONObject(0);asset.put("digest","invalid");rejectRelease(json);asset.put("digest","sha256:"+HASH);
        asset.put("browser_download_url","https://github.com/someone/other/releases/download/v1.4.0/Carnet.apk");rejectRelease(json);
        json=release();json.put("tag_name","v../../other");rejectRelease(json);
        json=release();json.getJSONArray("assets").getJSONObject(0).put("size",UpdateManager.MAX_APK+1);rejectRelease(json);
    }
    private void rejectRelease(JSONObject json) throws Exception {try{UpdateManager.parseRelease(json);fail("Untrusted release accepted");}catch(IOException|IllegalArgumentException expected){}}
    @Test public void redirectsRemainHttpsOnGitHubHostsOnly() {
        assertTrue(UpdateManager.allowedUrl("https://release-assets.githubusercontent.com/path?token=public"));
        assertFalse(UpdateManager.allowedUrl("http://github.com/file"));assertFalse(UpdateManager.allowedUrl("https://github.com.attacker.test/file"));
        assertFalse(UpdateManager.allowedUrl("https://user:secret@github.com/file"));assertFalse(UpdateManager.allowedUrl("https://127.0.0.1/file"));assertFalse(UpdateManager.allowedUrl("https://github.com:8443/file"));
    }
    @Test public void downloadRequiresExactLengthAndSha256() throws Exception {
        UpdateManager.Release parsed=UpdateManager.parseRelease(release());ByteArrayOutputStream out=new ByteArrayOutputStream();
        UpdateManager.copyVerified(new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)),out,parsed);assertEquals("abc",out.toString("UTF-8"));
        for(String bad:new String[]{"ab","abcd","xyz"}) {try{UpdateManager.copyVerified(new ByteArrayInputStream(bad.getBytes(StandardCharsets.UTF_8)),new ByteArrayOutputStream(),parsed);fail("Bad APK accepted");}catch(IOException expected){}}
    }
    @Test @Config(sdk=26) public void apkMustHaveSamePackageAndSignerAndHigherVersion() throws Exception {
        Context context=RuntimeEnvironment.getApplication();PackageManager pm=context.getPackageManager();
        PackageInfo current=pm.getPackageInfo(context.getPackageName(),PackageManager.GET_SIGNATURES);current.signatures=new Signature[]{new Signature("abcd")};current.versionCode=4;Shadows.shadowOf(pm).installPackage(current);
        PackageInfo next=new PackageInfo();next.packageName=context.getPackageName();next.versionName="1.4.0";next.versionCode=5;next.signatures=current.signatures;
        File file=new File(context.getCacheDir(),"candidate.apk");Shadows.shadowOf(pm).setPackageArchiveInfo(file.getAbsolutePath(),next);
        UpdateManager.Release release=UpdateManager.parseRelease(release());UpdateManager.verifyArchive(context,file,release);
        next.signatures=new Signature[]{new Signature("cdef")};rejectArchive(context,file,release);next.signatures=current.signatures;
        next.versionCode=4;rejectArchive(context,file,release);next.versionCode=5;
        next.packageName="other.app";rejectArchive(context,file,release);
    }
    @Test public void modernAndroidRequiresMatchingSigningCertificate() throws Exception {
        Context context=RuntimeEnvironment.getApplication();PackageManager pm=context.getPackageManager();
        PackageInfo current=pm.getPackageInfo(context.getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES);
        current.signingInfo=new android.content.pm.SigningInfo();
        Shadows.shadowOf(current.signingInfo).setSignatures(new Signature[]{new Signature("abcd")});
        current.setLongVersionCode(4);Shadows.shadowOf(pm).installPackage(current);
        PackageInfo next=new PackageInfo();next.packageName=context.getPackageName();next.versionName="1.4.0";next.setLongVersionCode(5);
        next.signingInfo=new android.content.pm.SigningInfo();
        Shadows.shadowOf(next.signingInfo).setSignatures(new Signature[]{new Signature("abcd")});
        File file=new File(context.getCacheDir(),"candidate.apk");Shadows.shadowOf(pm).setPackageArchiveInfo(file.getAbsolutePath(),next);
        UpdateManager.Release release=UpdateManager.parseRelease(release());UpdateManager.verifyArchive(context,file,release);
        Shadows.shadowOf(next.signingInfo).setSignatures(new Signature[]{new Signature("cdef")});rejectArchive(context,file,release);
        next.signingInfo=null;rejectArchive(context,file,release);
    }
    private void rejectArchive(Context context,File file,UpdateManager.Release release) throws Exception {try{UpdateManager.verifyArchive(context,file,release);fail("Incompatible APK accepted");}catch(IOException expected){}}
    @Test public void providerExposesOnlyUpdateApkAndNeverAllowsWriting() throws Exception {
        Context context=RuntimeEnvironment.getApplication();File dir=new File(context.getCacheDir(),"updates");dir.mkdirs();
        try(FileOutputStream out=new FileOutputStream(new File(dir,"Carnet.apk"))){out.write(new byte[]{1,2,3});}
        UpdateFileProvider provider=new UpdateFileProvider();ProviderInfo info=new ProviderInfo();info.authority=context.getPackageName()+".updates";provider.attachInfo(context,info);
        Uri allowed=Uri.parse("content://"+info.authority+"/Carnet.apk");
        try(ParcelFileDescriptor file=provider.openFile(allowed,"r")){assertEquals(3,file.getStatSize());}
        try{provider.openFile(allowed,"w");fail("Write accepted");}catch(FileNotFoundException expected){}
        try{provider.openFile(Uri.parse("content://"+info.authority+"/../databases/carnet.db"),"r");fail("Other file exposed");}catch(IllegalArgumentException expected){}
    }
}
