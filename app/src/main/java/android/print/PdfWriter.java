package android.print;

import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;

import java.io.File;

/**
 * Запись содержимого WebView (PrintDocumentAdapter) в PDF-файл без окна печати.
 * Лежит в пакете android.print, потому что конструкторы LayoutResultCallback / WriteResultCallback
 * видны только внутри этого пакета.
 */
public final class PdfWriter {
    public interface Done {
        /** error == null — файл записан. */
        void onDone(String error);
    }

    private PdfWriter() {}

    public static void write(final PrintDocumentAdapter adapter, final PrintAttributes attrs,
                             final File out, final Done done) {
        try {
            adapter.onLayout(attrs, attrs, new CancellationSignal(),
                new PrintDocumentAdapter.LayoutResultCallback() {
                    @Override
                    public void onLayoutFinished(PrintDocumentInfo info, boolean changed) {
                        try {
                            final ParcelFileDescriptor fd = ParcelFileDescriptor.open(out,
                                ParcelFileDescriptor.MODE_READ_WRITE
                                    | ParcelFileDescriptor.MODE_CREATE
                                    | ParcelFileDescriptor.MODE_TRUNCATE);
                            adapter.onWrite(new PageRange[]{PageRange.ALL_PAGES}, fd, new CancellationSignal(),
                                new PrintDocumentAdapter.WriteResultCallback() {
                                    @Override
                                    public void onWriteFinished(PageRange[] pages) {
                                        try { fd.close(); } catch (Exception ignored) { }
                                        done.onDone(null);
                                    }

                                    @Override
                                    public void onWriteFailed(CharSequence error) {
                                        try { fd.close(); } catch (Exception ignored) { }
                                        done.onDone(error == null ? "ошибка записи PDF" : error.toString());
                                    }

                                    @Override
                                    public void onWriteCancelled() {
                                        try { fd.close(); } catch (Exception ignored) { }
                                        done.onDone("запись PDF отменена");
                                    }
                                });
                        } catch (Exception e) {
                            done.onDone(e.getMessage() == null ? "ошибка PDF" : e.getMessage());
                        }
                    }

                    @Override
                    public void onLayoutFailed(CharSequence error) {
                        done.onDone(error == null ? "ошибка разметки PDF" : error.toString());
                    }

                    @Override
                    public void onLayoutCancelled() {
                        done.onDone("разметка PDF отменена");
                    }
                }, null);
        } catch (Exception e) {
            done.onDone(e.getMessage() == null ? "ошибка PDF" : e.getMessage());
        }
    }
}
