package com.majkeylab.scanit;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.DocumentsContract;

// Runs in the standalone test APK without the target APK's Kotlin classpath.
public class SecurityGrantActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            grantUriPermission(
                SecurityDocumentsProvider.TARGET,
                DocumentsContract.buildTreeDocumentUri(SecurityDocumentsProvider.AUTHORITY, SecurityDocumentsProvider.ROOT),
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            );
        } finally {
            finish();
        }
    }
}
