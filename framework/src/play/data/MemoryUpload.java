package play.data;

import java.io.File;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import org.apache.commons.fileupload2.core.FileItem;

public class MemoryUpload implements Upload {

    final FileItem<?> fileItem;

    public MemoryUpload(FileItem<?> fileItem) {
        this.fileItem = fileItem;
    }

    @Override
    public File asFile() {
        throw new UnsupportedOperationException();
    }

    @Override
    public byte[] asBytes() {
        try {
            return fileItem.get();
        } catch (java.io.IOException e) {
            throw new play.exceptions.UnexpectedException(e);
        }
    }

    @Override
    public InputStream asStream() {
        try {
            return new ByteArrayInputStream(fileItem.get());
        } catch (java.io.IOException e) {
            throw new play.exceptions.UnexpectedException(e);
        }
    }

    @Override
    public String getContentType() {
        return fileItem.getContentType();
    }

    @Override
    public String getFileName() {
        return fileItem.getName();
    }

    @Override
    public String getFieldName() {
        return fileItem.getFieldName();
    }

    @Override
    public Long getSize() {
        return fileItem.getSize();
    }

    @Override
    public boolean isInMemory() {
        return fileItem.isInMemory();
    }

}
