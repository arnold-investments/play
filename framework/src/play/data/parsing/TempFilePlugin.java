package play.data.parsing;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;

import org.apache.commons.io.FileUtils;

import play.Play;
import play.PlayPlugin;
import play.mvc.Context;

/**
 *  Creates temporary folders for file parsing, and deletes
 *  it after request completion.
 */
public class TempFilePlugin extends PlayPlugin {

    private static final DecimalFormat format = new DecimalFormat("##########");

    static {
        format.setMinimumIntegerDigits(10);
        format.setGroupingUsed(false);
    }
    private static long count = 0;

    private static synchronized long getCountLocal() {
        return count++;
    }
    public static final ThreadLocal<File> tempFolder = new ThreadLocal<>();

    public static File createTempFolder() {
        if (Play.tmpDir == null || Play.readOnlyTmp) {
            return null;
        }
        if (tempFolder.get() == null) {
            File file = new File(Play.tmpDir +
                    File.separator + "uploads" + File.separator +
                    System.currentTimeMillis() + "_" + format.format(getCountLocal()));
            file.mkdirs();
            tempFolder.set(file);
        }
        return tempFolder.get();
    }

    @Override
    public void invocationFinally(Context context) {
        File file = tempFolder.get();
        if (file != null) {
            tempFolder.remove();
            try {
                FileUtils.deleteDirectory(file);
            } catch (IOException e) {
            }
        }
    }
}
