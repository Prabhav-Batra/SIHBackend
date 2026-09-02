package com.sih26046.ctms.documents;

import java.io.InputStream;
import xyz.capybara.clamav.ClamavClient;
import xyz.capybara.clamav.commands.scan.result.ScanResult;

/**
 * Scans through clamd's INSTREAM command (§16.6).
 *
 * <p>The client is built eagerly but connects per scan, so the application starts even when
 * clamd is not yet up — which matters because clamd spends the better part of a minute loading
 * its signature database, and a backend that refused to boot until that finished would make
 * every deploy wait on it.
 */
public class ClamAvScanner implements MalwareScanner {

    private final ClamavClient client;

    public ClamAvScanner(DocumentProperties properties) {
        this.client = new ClamavClient(properties.clamav().host(), properties.clamav().port());
    }

    @Override
    public ScanVerdict scan(InputStream content) {
        ScanResult result = client.scan(content);
        // Anything that is not an explicit OK counts as infected. The alternative — defaulting
        // an unrecognised reply to CLEAN — fails open, and this is the one place in the system
        // where failing open means serving malware.
        return result instanceof ScanResult.OK ? ScanVerdict.CLEAN : ScanVerdict.INFECTED;
    }
}
