package play.data.parsing;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.apache.commons.io.FileUtils.readFileToByteArray;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileItemHeaders;
import org.apache.commons.fileupload.FileItemIterator;
import org.apache.commons.fileupload.FileItemStream;
import org.apache.commons.fileupload.FileUploadBase;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.ParameterParser;
import org.apache.commons.fileupload.RequestContext;
import org.apache.commons.fileupload.disk.DiskFileItem;
import org.apache.commons.fileupload.util.Closeable;
import org.apache.commons.fileupload.util.LimitedInputStream;
import org.apache.commons.fileupload.util.Streams;
import org.apache.commons.io.FileCleaningTracker;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.output.DeferredFileOutputStream;

import play.Logger;
import play.Play;
import play.data.FileUpload;
import play.data.MemoryUpload;
import play.data.Upload;
import play.exceptions.UnexpectedException;
import play.mvc.Http;
import play.mvc.Http.Request;
import play.utils.HTTP;

/**
 * From Apache commons fileupload. http://commons.apache.org/fileupload/
 */
public class ApacheMultipartParser extends DataParser {

    private static void putMapEntry(Map<String, String[]> map, String name, String value) {
        map.compute(name, (key, oldValues) -> {
            if (oldValues == null) {
                return new String[] { value };
            }

            String[] newValues = new String[oldValues.length + 1];
            System.arraycopy(oldValues, 0, newValues, 0, oldValues.length);
            newValues[oldValues.length] = value;

            return newValues;
        });
    }

    /*
     * Copyright 2001-2004 The Apache Software Foundation
     *
     * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance
     * with the License. You may obtain a copy of the License at
     *
     * http://www.apache.org/licenses/LICENSE-2.0
     *
     * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
     * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for
     * the specific language governing permissions and limitations under the License.
     *
     * <p> The default implementation of the {@link org.apache.commons.fileupload.FileItem FileItem} interface.
     *
     * <p> After retrieving an instance of this class from a {@link org.apache.commons.fileupload.DiskFileUpload
     * DiskFileUpload} instance (see {@link org.apache.commons.fileupload.DiskFileUpload
     * #parseRequest(javax.servlet.http.HttpServletRequest)}), you may either request all contents of file at once using
     * {@link #get()} or request an {@link java.io.InputStream InputStream} with {@link #getInputStream()} and process
     * the file without attempting to load it into memory, which may come handy with large files.
     *
     * @author <a href="mailto:Rafal.Krzewski@e-point.pl">Rafal Krzewski</a>
     * 
     * @author <a href="mailto:sean@informage.net">Sean Legassick</a>
     * 
     * @author <a href="mailto:jvanzyl@apache.org">Jason van Zyl</a>
     * 
     * @author <a href="mailto:jmcnally@apache.org">John McNally</a>
     * 
     * @author <a href="mailto:martinc@apache.org">Martin Cooper</a>
     * 
     * @author Sean C. Sullivan
     *
     * @since FileUpload 1.1
     *
     * @version $Id: DiskFileItem.java,v 1.3 2005/07/26 03:05:02 rafaelsteil Exp $
     */
    public static class AutoFileItem implements FileItem {

        private static final FileCleaningTracker fileTracker = new FileCleaningTracker();

        // ----------------------------------------------------- Manifest constants
        /**
         * Default content charset to be used when no explicit charset parameter is provided by the sender. Media
         * subtypes of the "text" type are defined to have a default charset value of "ISO-8859-1" when received via
         * HTTP.
         */
        public static final String DEFAULT_CHARSET = "ISO-8859-1";

        // ----------------------------------------------------------- Data members
        /**
         * Counter used in unique identifier generation.
         */
        private static int counter = 0;
        /**
         * The name of the form field as provided by the browser.
         */
        private String fieldName;
        /**
         * The content type passed by the browser, or <code>null</code> if not defined.
         */
        private String contentType;
        /**
         * Whether or not this item is a simple form field.
         */
        private boolean isFormField;
        /**
         * The original filename in the user's filesystem.
         */
        private String fileName;
        /**
         * The threshold above which uploads will be stored on disk.
         */
        private int sizeThreshold;
        /**
         * The directory in which uploaded files will be stored, if stored on disk.
         */
        private File repository;
        /**
         * Cached contents of the file.
         */
        private byte[] cachedContent;
        /**
         * Output stream for this item.
         */
        private DeferredFileOutputStream dfos;

        /**
         * The file items headers.
         */
        private FileItemHeaders headers;

        public AutoFileItem(FileItemStream stream) {
            this.fieldName = stream.getFieldName();
            this.contentType = stream.getContentType();
            this.isFormField = stream.isFormField();
            this.fileName = FilenameUtils.getName(stream.getName());
            this.sizeThreshold = Integer.parseInt(Play.configuration.getProperty("upload.threshold", "10240"));
            this.repository = null;
        }
        // ------------------------------- Methods from javax.activation.DataSource

        /**
         * Returns an {@link java.io.InputStream InputStream} that can be used to retrieve the contents of the file.
         *
         * @return An {@link java.io.InputStream InputStream} that can be used to retrieve the contents of the file.
         * @throws IOException
         *             if an error occurs.
         */
        @Override
        public InputStream getInputStream() throws IOException {
            if (!dfos.isInMemory()) {
                return new FileInputStream(dfos.getFile());
            }

            if (cachedContent == null) {
                cachedContent = dfos.getData();
            }
            return new ByteArrayInputStream(cachedContent);
        }

        /**
         * Returns the content type passed by the agent or <code>null</code> if not defined.
         *
         * @return The content type passed by the agent or <code>null</code> if not defined.
         */
        @Override
        public String getContentType() {
            return contentType;
        }

        /**
         * Returns the content charset passed by the agent or <code>null</code> if not defined.
         *
         * @return The content charset passed by the agent or <code>null</code> if not defined.
         */
        public String getCharSet() {
            ParameterParser parser = new ParameterParser();
            parser.setLowerCaseNames(true);
            // Parameter parser can handle null input
            Map<String, String> params = parser.parse(getContentType(), ';');
            return params.get("charset");
        }

        /**
         * Returns the original filename in the client's filesystem.
         *
         * @return The original filename in the client's filesystem.
         */
        @Override
        public String getName() {
            return fileName;
        }
        // ------------------------------------------------------- FileItem methods

        /**
         * Provides a hint as to whether or not the file contents will be read from memory.
         *
         * @return <code>true</code> if the file contents will be read from memory; <code>false</code> otherwise.
         */
        @Override
        public boolean isInMemory() {
            return (dfos.isInMemory());
        }

        /**
         * Returns the size of the file.
         *
         * @return The size of the file, in bytes.
         */
        @Override
        public long getSize() {
            if (cachedContent != null) {
                return cachedContent.length;
            } else if (dfos.isInMemory()) {
                return dfos.getData().length;
            } else {
                return dfos.getFile().length();
            }
        }

        /**
         * Returns the contents of the file as an array of bytes. If the contents of the file were not yet cached in
         * memory, they will be loaded from the disk storage and cached.
         *
         * @return The contents of the file as an array of bytes.
         */
        @Override
        public byte[] get() {
            if (dfos.isInMemory()) {
                if (cachedContent == null) {
                    cachedContent = dfos.getData();
                }
                return cachedContent;
            }

            try {
                return readFileToByteArray(dfos.getFile());
            } catch (IOException e) {
                return null;
            }
        }

        /**
         * Returns the contents of the file as a String, using the specified encoding. This method uses {@link #get()}
         * to retrieve the contents of the file.
         *
         * @param charset
         *            The charset to use.
         * @return The contents of the file, as a string.
         * @throws UnsupportedEncodingException
         *             if the requested character encoding is not available.
         */
        @Override
        public String getString(String charset) throws UnsupportedEncodingException {
            return new String(get(), charset);
        }

        /**
         * Returns the contents of the file as a String, using the default character encoding. This method uses
         * {@link #get()} to retrieve the contents of the file.
         * <p>
         * 
         * @play.todo Note: TODO Consider making this method throw UnsupportedEncodingException .
         *            </p>
         * 
         * @return The contents of the file, as a string.
         */
        @Override
        public String getString() {
            byte[] rawdata = get();
            String charset = getCharSet();
            if (charset == null) {
                charset = DEFAULT_CHARSET;
            }
            try {
                return new String(rawdata, charset);
            } catch (UnsupportedEncodingException e) {
                return new String(rawdata);
            }
        }

        /**
         * A convenience method to write an uploaded item to disk. The client code is not concerned with whether or not
         * the item is stored in memory, or on disk in a temporary location. They just want to write the uploaded item
         * to a file.
         * <p>
         * This implementation first attempts to rename the uploaded item to the specified destination file, if the item
         * was originally written to disk. Otherwise, the data will be copied to the specified file.
         * <p>
         * This method is only guaranteed to work <em>once</em>, the first time it is invoked for a particular item.
         * This is because, in the event that the method renames a temporary file, that file will no longer be available
         * to copy or rename again at a later time.
         *
         * @param file
         *            The <code>File</code> into which the uploaded item should be stored.
         * @throws Exception
         *             if an error occurs.
         */
        @Override
        public void write(File file) throws Exception {
            if (isInMemory()) {
                FileUtils.writeByteArrayToFile(file, get());
            } else {
                File outputFile = getStoreLocation();
                if (outputFile != null) {
                    /*
                     * The uploaded file is being stored on disk in a temporary location so move it to the desired file.
                     */
                    if (!outputFile.renameTo(file)) {
                        FileUtils.copyFile(outputFile, file);
                    }
                } else {
                    /*
                     * For whatever reason we cannot write the file to disk.
                     */
                    throw new FileUploadException("Cannot write uploaded file to disk!");
                }
            }
        }

        /**
         * Deletes the underlying storage for a file item, including deleting any associated temporary disk file.
         * Although this storage will be deleted automatically when the <code>FileItem</code> instance is garbage
         * collected, this method can be used to ensure that this is done at an earlier time, thus preserving system
         * resources.
         */
        @Override
        public void delete() {
            cachedContent = null;
            File outputFile = getStoreLocation();
            if (outputFile != null && outputFile.exists()) {
                outputFile.delete();
            }
        }

        /**
         * Returns the name of the field in the multipart form corresponding to this file item.
         *
         * @return The name of the form field.
         * @see #setFieldName(java.lang.String)
         */
        @Override
        public String getFieldName() {
            return fieldName;
        }

        /**
         * Sets the field name used to reference this file item.
         *
         * @param fieldName
         *            The name of the form field.
         * @see #getFieldName()
         */
        @Override
        public void setFieldName(String fieldName) {
            this.fieldName = fieldName;
        }

        /**
         * Determines whether or not a <code>FileItem</code> instance represents a simple form field.
         *
         * @return <code>true</code> if the instance represents a simple form field; <code>false</code> if it represents
         *         an uploaded file.
         * @see #setFormField(boolean)
         */
        @Override
        public boolean isFormField() {
            return isFormField;
        }

        /**
         * Specifies whether or not a <code>FileItem</code> instance represents a simple form field.
         *
         * @param state
         *            <code>true</code> if the instance represents a simple form field; <code>false</code> if it
         *            represents an uploaded file.
         * @see #isFormField()
         */
        @Override
        public void setFormField(boolean state) {
            isFormField = state;
        }

        /**
         * Returns an {@link java.io.OutputStream OutputStream} that can be used for storing the contents of the file.
         *
         * @return An {@link java.io.OutputStream OutputStream} that can be used for storing the contents of the file.
         * @throws IOException
         *             if an error occurs.
         */
        @Override
        public OutputStream getOutputStream() throws IOException {
            if (dfos == null) {
                File outputFile = null;
                if (sizeThreshold != Integer.MAX_VALUE) {
                    outputFile = getTempFile();
                }
                dfos = new DeferredFileOutputStream(sizeThreshold, outputFile);
            }
            return dfos;
        }
        // --------------------------------------------------------- Public methods

        /**
         * Returns the {@link java.io.File} object for the <code>FileItem</code>'s data's temporary location on the
         * disk. Note that for <code>FileItem</code>s that have their data stored in memory, this method will return
         * <code>null</code>. When handling large files, you can use {@link java.io.File#renameTo(java.io.File)} to move
         * the file to new location without copying the data, if the source and destination locations reside within the
         * same logical volume.
         *
         * @return The data file, or <code>null</code> if the data is stored in memory.
         */
        public File getStoreLocation() {
            return dfos.getFile();
        }
        // ------------------------------------------------------ Protected methods

        /**
         * Creates and returns a {@link java.io.File File} representing a uniquely named temporary file in the
         * configured repository path. The lifetime of the file is tied to the lifetime of the <code>FileItem</code>
         * instance; the file will be deleted when the instance is garbage collected.
         *
         * @return The {@link java.io.File File} to be used for temporary storage.
         */
        protected File getTempFile() {
            File tempDir = repository;
            if (tempDir == null) {
                tempDir = Play.tmpDir;
            }

            String fileName = "upload_" + getUniqueId() + ".tmp";

            File f = new File(tempDir, fileName);
            fileTracker.track(f, this);
            return f;
        }
        // -------------------------------------------------------- Private methods

        /**
         * Returns an identifier that is unique within the class loader used to load this class, but does not have
         * random-like apearance.
         *
         * @return A String with the non-random looking instance identifier.
         */
        private static String getUniqueId() {
            int current;
            synchronized (DiskFileItem.class) {
                current = counter++;
            }
            String id = Integer.toString(current);

            // If you manage to get more than 100 million of ids, you'll
            // start getting ids longer than 8 characters.
            if (current < 100000000) {
                id = ("00000000" + id).substring(id.length());
            }
            return id;
        }

        @Override
        public String toString() {
            return "name=" + this.getName() + ", StoreLocation=" + String.valueOf(this.getStoreLocation()) + ", size=" + this.getSize()
                    + "bytes, " + "isFormField=" + isFormField() + ", FieldName=" + this.getFieldName();
        }

        /**
         * Returns the file item headers.
         * 
         * @return The file items headers.
         */
        @Override
        public FileItemHeaders getHeaders() {
            return headers;
        }

        /**
         * Sets the file item headers.
         * 
         * @param pHeaders
         *            The file items headers.
         */
        @Override
        public void setHeaders(FileItemHeaders pHeaders) {
            headers = pHeaders;
        }
    }

    @Override
    public Map<String, String[]> parse(Http.Request request, InputStream body) {
        Map<String, String[]> result = new HashMap<>();
        try {
            FileItemIteratorImpl iter = new FileItemIteratorImpl(body, request.headers.get("content-type").value(),
                request.encoding);
            while (iter.hasNext()) {
                FileItemStream item = iter.next();
                FileItem fileItem = new AutoFileItem(item);
                try {
                    try {
                        Streams.copy(item.openStream(), fileItem.getOutputStream(), true);
                    } catch (FileUploadIOException e) {
                        throw (FileUploadException) e.getCause();
                    } catch (IOException e) {
                        throw new IOFileUploadException("Processing of " + MULTIPART_FORM_DATA + " request failed. " + e.getMessage(), e);
                    }
                    if (fileItem.isFormField()) {
                        // must resolve encoding
                        String _encoding = request.encoding; // this is our default
                        String _contentType = fileItem.getContentType();
                        if (_contentType != null) {
                            HTTP.ContentTypeWithEncoding contentTypeEncoding = HTTP.parseContentType(_contentType);
                            if (contentTypeEncoding.encoding != null) {
                                _encoding = contentTypeEncoding.encoding;
                            }
                        }

                        putMapEntry(result, fileItem.getFieldName(), fileItem.getString(_encoding));
                    } else {
                        @SuppressWarnings("unchecked")
                        List<Upload> uploads = (List<Upload>) request.args.computeIfAbsent("__UPLOADS", k -> new ArrayList<>());
                        try {
                            uploads.add(new FileUpload(fileItem));
                        } catch (Exception e) {
                            // GAE does not support it, we try in memory
                            uploads.add(new MemoryUpload(fileItem));
                        }
                        putMapEntry(result, fileItem.getFieldName(), fileItem.getFieldName());
                    }
                } finally {
                    fileItem.delete();
                }
            }
        } catch (IOException | FileUploadException e) {
            Logger.debug(e, "error");
            throw new IllegalStateException("Error when handling upload", e);
        } catch (Exception e) {
            Logger.debug(e, "error");
            throw new UnexpectedException(e);
        }
        return result;
    } // ---------------------------------------------------------- Class methods
      // ----------------------------------------------------- Manifest constants

    /**
     * HTTP content type header name.
     */
    private static final String CONTENT_TYPE = "Content-type";
    /**
     * HTTP content disposition header name.
     */
    private static final String CONTENT_DISPOSITION = "Content-disposition";
    /**
     * Content-disposition value for form data.
     */
    private static final String FORM_DATA = "form-data";
    /**
     * Content-disposition value for file attachment.
     */
    private static final String ATTACHMENT = "attachment";
    /**
     * Part of HTTP content type header.
     */
    private static final String MULTIPART = "multipart/";
    /**
     * HTTP content type header for multipart forms.
     */
    private static final String MULTIPART_FORM_DATA = "multipart/form-data";
    /**
     * HTTP content type header for multiple uploads.
     */
    private static final String MULTIPART_MIXED = "multipart/mixed";
    // ----------------------------------------------------------- Data members
    /**
     * The maximum size permitted for the complete request, as opposed to
     * {@link #maxFileSize}. A value of -1 indicates no maximum.
     */
    private final long maxRequestSize = Integer.parseInt(Play.configuration.getProperty("upload.maxRequestSize", "-1"));
    /**
     * The maximum size permitted for a single uploaded file, as opposed to
     * {@link #maxRequestSize}. A value of -1 indicates no maximum.
     */
    private final long maxFileSize = Integer.parseInt(Play.configuration.getProperty("upload.maxFileSize", "-1"));

    // ------------------------------------------------------ Protected methods

    /**
     * Retrieves the boundary from the <code>Content-type</code> header.
     *
     * @param contentType
     *            The value of the content type header from which to extract the boundary value.
     * @return The boundary, as a byte array.
     */
    private byte[] getBoundary(String contentType) {

        ParameterParser parser = new ParameterParser();
        parser.setLowerCaseNames(true);
        // Parameter parser can handle null input
        Map<String, String> params = parser.parse(contentType, ';');
        String boundaryStr = params.get("boundary");

        if (boundaryStr == null) {
            return null;
        }
        return boundaryStr.getBytes(ISO_8859_1);
    }

    /**
     * Retrieves the file name from the <code>Content-disposition</code> header.
     *
     * @param headers
     *            A <code>Map</code> containing the HTTP request headers.
     * @return The file name for the current <code>encapsulation</code>.
     */
    private String getFileName(Map<String, String> headers) {
        String fileName = null;
        String cd = getHeader(headers, CONTENT_DISPOSITION);
        if (cd != null) {
            String cdl = cd.toLowerCase();
            if (cdl.startsWith(FORM_DATA) || cdl.startsWith(ATTACHMENT)) {
                ParameterParser parser = new ParameterParser();
                parser.setLowerCaseNames(true);
                // Parameter parser can handle null input
                Map<String, String> params = parser.parse(cd, ';');
                if (params.containsKey("filename")) {
                    fileName = params.get("filename");
                    if (fileName != null) {
                        fileName = fileName.trim();
                        // IE7 returning fullpath name (#300920)
                        if (fileName.indexOf('\\') != -1) {
                            fileName = fileName.substring(fileName.lastIndexOf('\\') + 1);
                        }

                    } else {
                        // Even if there is no value, the parameter is present,
                        // so we return an empty file name rather than no file
                        // name.
                        fileName = "";
                    }
                }
            }
        }
        return fileName;
    }

    /**
     * Retrieves the field name from the <code>Content-disposition</code> header.
     *
     * @param headers
     *            A <code>Map</code> containing the HTTP request headers.
     * @return The field name for the current <code>encapsulation</code>.
     */
    private String getFieldName(Map<String, String> headers) {
        String fieldName = null;
        String cd = getHeader(headers, CONTENT_DISPOSITION);
        if (cd != null && (cd.toLowerCase().startsWith(FORM_DATA) || cd.toLowerCase().startsWith(ATTACHMENT))) {

            ParameterParser parser = new ParameterParser();
            parser.setLowerCaseNames(true);
            // Parameter parser can handle null input
            Map<String, String> params = parser.parse(cd, ';');
            fieldName = params.get("name");
            if (fieldName != null) {
                fieldName = fieldName.trim();
            }
        }
        return fieldName;
    }

    /**
     * <p/>
     * Parses the <code>header-part</code> and returns as key/value pairs.
     * <p/>
     * <p/>
     * If there are multiple headers of the same names, the name will map to a comma-separated list containing the
     * values.
     *
     * @param headerPart
     *            The <code>header-part</code> of the current <code>encapsulation</code>.
     * @return A <code>Map</code> containing the parsed HTTP request headers.
     */
    private Map<String, String> parseHeaders(String headerPart) {
        int len = headerPart.length();
        Map<String, String> headers = new HashMap<>();
        int start = 0;
        for (;;) {
            int end = parseEndOfLine(headerPart, start);
            if (start == end) {
                break;
            }
            String header = headerPart.substring(start, end);
            start = end + 2;
            while (start < len) {
                int nonWs = start;
                while (nonWs < len) {
                    char c = headerPart.charAt(nonWs);
                    if (c != ' ' && c != '\t') {
                        break;
                    }
                    ++nonWs;
                }
                if (nonWs == start) {
                    break;
                }
                // Continuation line found
                end = parseEndOfLine(headerPart, nonWs);
                header += " " + headerPart.substring(nonWs, end);
                start = end + 2;
            }
            parseHeaderLine(headers, header);
        }
        return headers;
    }

    /**
     * Skips bytes until the end of the current line.
     *
     * @param headerPart
     *            The headers, which are being parsed.
     * @param end
     *            Index of the last byte, which has yet been processed.
     * @return Index of the \r\n sequence, which indicates end of line.
     */
    private int parseEndOfLine(String headerPart, int end) {
        int index = end;
        for (;;) {
            int offset = headerPart.indexOf('\r', index);
            if (offset == -1 || offset + 1 >= headerPart.length()) {
                throw new IllegalStateException("Expected headers to be terminated by an empty line.");
            }
            if (headerPart.charAt(offset + 1) == '\n') {
                return offset;
            }
            index = offset + 1;
        }
    }

    /**
     * Reads the next header line.
     *
     * @param headers
     *            String with all headers.
     * @param header
     *            Map where to store the current header.
     */
    private void parseHeaderLine(Map<String, String> headers, String header) {
        int colonOffset = header.indexOf(':');
        if (colonOffset == -1) {
            // This header line is malformed, skip it.
            return;
        }
        String headerName = header.substring(0, colonOffset).trim().toLowerCase();
        String headerValue = header.substring(header.indexOf(':') + 1).trim();
        if (getHeader(headers, headerName) != null) {
            // More that one heder of that name exists,
            // append to the list.
            headers.put(headerName, getHeader(headers, headerName) + ',' + headerValue);
        } else {
            headers.put(headerName, headerValue);
        }
    }

    /**
     * Returns the header with the specified name from the supplied map. The header lookup is case-insensitive.
     *
     * @param headers
     *            A <code>Map</code> containing the HTTP request headers.
     * @param name
     *            The name of the header to return.
     * @return The value of specified header, or a comma-separated list if there were multiple headers of that name.
     */
    private String getHeader(Map<String, String> headers, String name) {
        return headers.get(name.toLowerCase());
    }

    /**
     * The iterator, which is returned by {@link FileUploadBase#getItemIterator(RequestContext)}.
     */
    private class FileItemIteratorImpl implements FileItemIterator {

        /**
         * Default implementation of {@link FileItemStream}.
         */
        private class FileItemStreamImpl implements FileItemStream {

            /**
             * The file items content type.
             */
            private final String contentType;
            /**
             * The file items field name.
             */
            private final String fieldName;
            /**
             * The file items file name.
             */
            private final String name;
            /**
             * Whether the file item is a form field.
             */
            private final boolean formField;
            /**
             * The file items input stream.
             */
            private final InputStream stream;
            /**
             * Whether the file item was already opened.
             */
            private boolean opened;

            private FileItemHeaders fileItemHeaders;

            /**
             * CReates a new instance.
             *
             * @param pName
             *            The items file name, or null.
             * @param pFieldName
             *            The items field name.
             * @param pContentType
             *            The items content type, or null.
             * @param pFormField
             *            Whether the item is a form field.
             */
            FileItemStreamImpl(String pName, String pFieldName, String pContentType, boolean pFormField) {
                name = pName;
                fieldName = pFieldName;
                contentType = pContentType;
                formField = pFormField;
                InputStream istream = multi.newInputStream();
                if (maxFileSize != -1) {
                    istream = new LimitedInputStream(istream, maxFileSize) {

                        @Override
                        protected void raiseError(long pSizeMax, long pCount) throws IOException {
                            FileUploadException e = new FileSizeLimitExceededException(
                                    "The field " + fieldName + " exceeds its maximum permitted " + " size of " + pSizeMax + " characters.",
                                    pCount, pSizeMax);
                            throw new FileUploadIOException(e);
                        }
                    };
                }
                stream = istream;
            }

            @Override
            public FileItemHeaders getHeaders() {
                return fileItemHeaders;
            }

            @Override
            public void setHeaders(FileItemHeaders fileItemHeaders) {
                this.fileItemHeaders = fileItemHeaders;
            }

            /**
             * Returns the items content type, or null.
             *
             * @return Content type, if known, or null.
             */
            @Override
            public String getContentType() {
                return contentType;
            }

            /**
             * Returns the items field name.
             *
             * @return Field name.
             */
            @Override
            public String getFieldName() {
                return fieldName;
            }

            /**
             * Returns the items file name.
             *
             * @return File name, if known, or null.
             */
            @Override
            public String getName() {
                return name;
            }

            /**
             * Returns, whether this is a form field.
             *
             * @return True, if the item is a form field, otherwise false.
             */
            @Override
            public boolean isFormField() {
                return formField;
            }

            /**
             * Returns an input stream, which may be used to read the items contents.
             *
             * @return Opened input stream.
             * @throws IOException
             *             An I/O error occurred.
             */
            @Override
            public InputStream openStream() throws IOException {
                if (opened) {
                    throw new IllegalStateException("The stream was already opened.");
                }
                if (((Closeable) stream).isClosed()) {
                    throw new FileItemStream.ItemSkippedException();
                }
                return stream;
            }

            /**
             * Closes the file item.
             *
             * @throws IOException
             *             An I/O error occurred.
             */
            void close() throws IOException {
                stream.close();
            }
        }

        /**
         * The multi part stream to process.
         */
        private final MultipartStream multi;
        /**
         * The boundary, which separates the various parts.
         */
        private final byte[] boundary;
        /**
         * The item, which we currently process.
         */
        private FileItemStreamImpl currentItem;
        /**
         * The current items field name.
         */
        private String currentFieldName;
        /**
         * Whether we are currently skipping the preamble.
         */
        private boolean skipPreamble;
        /**
         * Whether the current item may still be read.
         */
        private boolean itemValid;
        /**
         * Whether we have seen the end of the file.
         */
        private boolean eof;

        /**
         * Creates a new instance.
         *
         * @throws FileUploadException
         *             An error occurred while parsing the request.
         * @throws IOException
         *             An I/O error occurred.
         */
        FileItemIteratorImpl(InputStream input, String contentType, String charEncoding) throws FileUploadException, IOException {

            if ((null == contentType) || (!contentType.toLowerCase().startsWith(MULTIPART))) {
                throw new InvalidContentTypeException("the request doesn't contain a " + MULTIPART_FORM_DATA + " or " + MULTIPART_MIXED
                        + " stream, content type header is " + contentType);
            }

            if (maxRequestSize >= 0) {
                // TODO check size

                input = new LimitedInputStream(input, maxRequestSize) {

                    @Override
                    protected void raiseError(long pSizeMax, long pCount) throws IOException {
                        FileUploadException ex = new SizeLimitExceededException("the request was rejected because" + " its size (" + pCount
                                + ") exceeds the configured maximum" + " (" + pSizeMax + ")", pCount, pSizeMax);
                        throw new FileUploadIOException(ex);
                    }
                };

            }

            boundary = getBoundary(contentType);
            if (boundary == null) {
                throw new FileUploadException("the request was rejected because " + "no multipart boundary was found");
            }

            multi = new MultipartStream(input, boundary, null);
            multi.setHeaderEncoding(charEncoding);

            skipPreamble = true;
            findNextItem();
        }

        /**
         * Called for finding the nex item, if any.
         *
         * @return True, if an next item was found, otherwise false.
         * @throws IOException
         *             An I/O error occurred.
         */
        private boolean findNextItem() throws IOException {
            if (eof) {
                return false;
            }
            if (currentItem != null) {
                currentItem.close();
                currentItem = null;
            }
            for (;;) {
                boolean nextPart;
                if (skipPreamble) {
                    nextPart = multi.skipPreamble();
                } else {
                    nextPart = multi.readBoundary();
                }
                if (!nextPart) {
                    if (currentFieldName == null) {
                        // Outer multipart terminated -> No more data
                        eof = true;
                        return false;
                    }
                    // Inner multipart terminated -> Return to parsing the outer
                    multi.setBoundary(boundary);
                    currentFieldName = null;
                    continue;
                }
                Map<String, String> headers = parseHeaders(multi.readHeaders());
                if (currentFieldName == null) {
                    // We're parsing the outer multipart
                    String fieldName = getFieldName(headers);
                    if (fieldName != null) {
                        String subContentType = getHeader(headers, CONTENT_TYPE);
                        if (subContentType != null && subContentType.toLowerCase().startsWith(MULTIPART_MIXED)) {
                            currentFieldName = fieldName;
                            // Multiple files associated with this field name
                            byte[] subBoundary = getBoundary(subContentType);
                            multi.setBoundary(subBoundary);
                            skipPreamble = true;
                            continue;
                        }
                        String fileName = getFileName(headers);
                        currentItem = new FileItemStreamImpl(fileName, fieldName, getHeader(headers, CONTENT_TYPE), fileName == null);

                        itemValid = true;
                        return true;
                    }
                } else {
                    String fileName = getFileName(headers);
                    if (fileName != null) {
                        currentItem = new FileItemStreamImpl(fileName, currentFieldName, getHeader(headers, CONTENT_TYPE), false);
                        itemValid = true;
                        return true;
                    }
                }
                multi.discardBodyData();
            }
        }

        /**
         * Returns, whether another instance of {@link FileItemStream} is available.
         *
         * @return True, if one or more additional file items are available, otherwise false.
         * @throws FileUploadException
         *             Parsing or processing the file item failed.
         * @throws IOException
         *             Reading the file item failed.
         */
        @Override
        public boolean hasNext() throws FileUploadException, IOException {
            if (eof) {
                return false;
            }
            if (itemValid) {
                return true;
            }
            return findNextItem();
        }

        /**
         * Returns the next available {@link FileItemStream}.
         *
         * @return FileItemStream instance, which provides access to the next file item.
         * @throws java.util.NoSuchElementException
         *             No more items are available. Use {@link #hasNext()} to prevent this exception.
         * @throws FileUploadException
         *             Parsing or processing the file item failed.
         * @throws IOException
         *             Reading the file item failed.
         */
        @Override
        public FileItemStream next() throws FileUploadException, IOException {
            if (eof || (!itemValid && !hasNext())) {
                throw new NoSuchElementException();
            }
            itemValid = false;
            return currentItem;
        }
    }

    /**
     * This exception is thrown for hiding an inner {@link FileUploadException} in an {@link IOException}.
     */
    private static class FileUploadIOException extends IOException {

        /**
         * The exceptions UID, for serializing an instance.
         */
        private static final long serialVersionUID = -7047616958165584154L;
        /**
         * The exceptions cause; we overwrite the parent classes field, which is available since Java 1.4 only.
         */
        private final FileUploadException cause;

        /**
         * Creates a <code>FileUploadIOException</code> with the given cause.
         *
         * @param pCause
         *            The exceptions cause, if any, or null.
         */
        public FileUploadIOException(FileUploadException pCause) {
            // We're not doing super(pCause) cause of 1.3 compatibility.
            cause = pCause;
        }

        /**
         * Returns the exceptions cause.
         *
         * @return The exceptions cause, if any, or null.
         */
        @Override
        public Throwable getCause() {
            return cause;
        }
    }

    /**
     * Thrown to indicate that the request is not a multipart request.
     */
    private static class InvalidContentTypeException extends FileUploadException {

        /**
         * The exceptions UID, for serializing an instance.
         */
        private static final long serialVersionUID = -9073026332015646668L;

        /**
         * Constructs an <code>InvalidContentTypeException</code> with the specified detail message.
         *
         * @param message
         *            The detail message.
         */
        public InvalidContentTypeException(String message) {
            super(message);
        }
    }

    /**
     * Thrown to indicate an IOException.
     */
    private static class IOFileUploadException extends FileUploadException {

        /**
         * The exceptions UID, for serializing an instance.
         */
        private static final long serialVersionUID = 1749796615868477269L;
        /**
         * The exceptions cause; we overwrite the parent classes field, which is available since Java 1.4 only.
         */
        private final IOException cause;

        /**
         * Creates a new instance with the given cause.
         *
         * @param pMsg
         *            The detail message.
         * @param pException
         *            The exceptions cause.
         */
        public IOFileUploadException(String pMsg, IOException pException) {
            super(pMsg);
            cause = pException;
        }

        /**
         * Returns the exceptions cause.
         *
         * @return The exceptions cause, if any, or null.
         */
        @Override
        public Throwable getCause() {
            return cause;
        }
    }

    /**
     * This exception is thrown, if a requests permitted size is exceeded.
     */
    protected abstract static class SizeException extends FileUploadException {

        /**
         * The actual size of the request.
         */
        private final long actual;
        /**
         * The maximum permitted size of the request.
         */
        private final long permitted;

        /**
         * Creates a new instance.
         *
         * @param message
         *            The detail message.
         * @param actual
         *            The actual number of bytes in the request.
         * @param permitted
         *            The requests size limit, in bytes.
         */
        protected SizeException(String message, long actual, long permitted) {
            super(message);
            this.actual = actual;
            this.permitted = permitted;
        }

        /**
         * Retrieves the actual size of the request.
         *
         * @return The actual size of the request.
         */
        public long getActualSize() {
            return actual;
        }

        /**
         * Retrieves the permitted size of the request.
         *
         * @return The permitted size of the request.
         */
        public long getPermittedSize() {
            return permitted;
        }
    }

    /**
     * Thrown to indicate that the request size exceeds the configured maximum.
     */
    private static class SizeLimitExceededException extends SizeException {

        /**
         * The exceptions UID, for serializing an instance.
         */
        private static final long serialVersionUID = -2474893167098052828L;

        /**
         * Constructs a <code>SizeExceededException</code> with the specified detail message, and actual and permitted
         * sizes.
         *
         * @param message
         *            The detail message.
         * @param actual
         *            The actual request size.
         * @param permitted
         *            The maximum permitted request size.
         */
        public SizeLimitExceededException(String message, long actual, long permitted) {
            super(message, actual, permitted);
        }
    }

    /**
     * Thrown to indicate that A files size exceeds the configured maximum.
     */
    private static class FileSizeLimitExceededException extends SizeException {

        /**
         * The exceptions UID, for serializing an instance.
         */
        private static final long serialVersionUID = 8150776562029630058L;

        /**
         * Constructs a <code>SizeExceededException</code> with the specified detail message, and actual and permitted
         * sizes.
         *
         * @param message
         *            The detail message.
         * @param actual
         *            The actual request size.
         * @param permitted
         *            The maximum permitted request size.
         */
        public FileSizeLimitExceededException(String message, long actual, long permitted) {
            super(message, actual, permitted);
        }
    }
}
