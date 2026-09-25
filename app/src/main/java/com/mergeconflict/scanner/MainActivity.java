package com.mergeconflict.scanner;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.yalantis.ucrop.UCrop;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private ImageView preview;
    private TextView networkStatus;
    private TextView pageCount;
    private Button sharePdfButton;

    @Nullable private Uri currentImageUri;
    @Nullable private Uri pendingCameraUri;
    @Nullable private File lastPdfFile;
    private boolean currentAddedToPages = false;

    private final List<Uri> pages = new ArrayList<>();
    @Nullable private ConnectivityManager connectivityManager;
    private boolean networkCallbackRegistered = false;

    private final ActivityResultLauncher<Intent> cameraLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && pendingCameraUri != null) {
                    setCurrentImage(pendingCameraUri);
                } else if (result.getResultCode() != Activity.RESULT_CANCELED) {
                    toast("La prise de photo n’a pas abouti.");
                }
                pendingCameraUri = null;
            });

    private final ActivityResultLauncher<String> importLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri == null) return;
                try {
                    Uri localCopy = copyUriToCache(uri, "import_");
                    setCurrentImage(localCopy);
                } catch (IOException e) {
                    toast("Impossible d’importer cette image.");
                }
            });

    private final ActivityResultLauncher<Intent> cropLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                Intent data = result.getData();
                if (result.getResultCode() == Activity.RESULT_OK && data != null) {
                    Uri output = UCrop.getOutput(data);
                    if (output != null) {
                        setCurrentImage(output);
                        toast("Image rognée.");
                    }
                } else if (result.getResultCode() == UCrop.RESULT_ERROR && data != null) {
                    Throwable error = UCrop.getError(data);
                    toast(error != null ? "Erreur de rognage : " + error.getMessage() : "Erreur de rognage.");
                }
            });

    private final ConnectivityManager.NetworkCallback networkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    refreshNetworkStatus();
                }

                @Override
                public void onLost(@NonNull Network network) {
                    refreshNetworkStatus();
                }

                @Override
                public void onCapabilitiesChanged(@NonNull Network network,
                                                  @NonNull NetworkCapabilities networkCapabilities) {
                    refreshNetworkStatus();
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        preview = findViewById(R.id.preview);
        networkStatus = findViewById(R.id.networkStatus);
        pageCount = findViewById(R.id.pageCount);
        sharePdfButton = findViewById(R.id.sharePdfButton);
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        findViewById(R.id.takePhotoButton).setOnClickListener(v -> takePhoto());
        findViewById(R.id.importButton).setOnClickListener(v -> importLauncher.launch("image/*"));
        findViewById(R.id.cropButton).setOnClickListener(v -> cropCurrentImage());
        findViewById(R.id.addPageButton).setOnClickListener(v -> addCurrentPage());
        findViewById(R.id.createPdfButton).setOnClickListener(v -> createPdf());
        sharePdfButton.setOnClickListener(v -> sharePdf());
        findViewById(R.id.resetButton).setOnClickListener(v -> resetSession());

        updatePageCount();
        refreshNetworkStatus();
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerNetworkCallback();
    }

    @Override
    protected void onStop() {
        unregisterNetworkCallback();
        super.onStop();
    }

    private void takePhoto() {
        try {
            File imageFile = createCacheFile("camera_", ".jpg");
            pendingCameraUri = fileUri(imageFile);

            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, pendingCameraUri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

            if (intent.resolveActivity(getPackageManager()) == null) {
                toast("Aucune application appareil photo disponible.");
                pendingCameraUri = null;
                return;
            }
            cameraLauncher.launch(intent);
        } catch (IOException e) {
            toast("Impossible de préparer la prise de photo.");
        }
    }

    private void cropCurrentImage() {
        if (currentImageUri == null) {
            toast("Prenez ou importez d’abord une photo.");
            return;
        }

        try {
            File outputFile = createCacheFile("crop_", ".jpg");
            Uri destination = fileUri(outputFile);

            UCrop.Options options = new UCrop.Options();
            options.setFreeStyleCropEnabled(true);
            options.setCompressionFormat(Bitmap.CompressFormat.JPEG);
            options.setCompressionQuality(92);
            options.setHideBottomControls(false);

            Intent cropIntent = UCrop.of(currentImageUri, destination)
                    .withOptions(options)
                    .getIntent(this);
            cropIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            cropLauncher.launch(cropIntent);
        } catch (IOException e) {
            toast("Impossible de préparer le rognage.");
        }
    }

    private void addCurrentPage() {
        if (currentImageUri == null) {
            toast("Aucune image à ajouter.");
            return;
        }
        if (!currentAddedToPages) {
            pages.add(currentImageUri);
            currentAddedToPages = true;
            updatePageCount();
            toast("Page ajoutée. Vous pouvez scanner la suivante.");
        } else {
            toast("Cette image est déjà dans le PDF.");
        }
    }

    private void createPdf() {
        List<Uri> pdfPages = new ArrayList<>(pages);
        if (currentImageUri != null && !currentAddedToPages) {
            pdfPages.add(currentImageUri);
        }
        if (pdfPages.isEmpty()) {
            toast("Ajoutez au moins une image.");
            return;
        }

        PdfDocument document = new PdfDocument();
        try {
            int pageNumber = 1;
            for (Uri imageUri : pdfPages) {
                Bitmap bitmap = decodeBitmapForPdf(imageUri);
                if (bitmap == null) {
                    throw new IOException("Image illisible");
                }

                boolean landscape = bitmap.getWidth() > bitmap.getHeight();
                int pageWidth = landscape ? 842 : 595;
                int pageHeight = landscape ? 595 : 842;

                PdfDocument.PageInfo info =
                        new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create();
                PdfDocument.Page page = document.startPage(info);
                Canvas canvas = page.getCanvas();
                canvas.drawColor(android.graphics.Color.WHITE);

                float margin = 24f;
                float availableWidth = pageWidth - (margin * 2f);
                float availableHeight = pageHeight - (margin * 2f);
                float scale =
                        Math.min(availableWidth / bitmap.getWidth(), availableHeight / bitmap.getHeight());
                float drawnWidth = bitmap.getWidth() * scale;
                float drawnHeight = bitmap.getHeight() * scale;
                float left = (pageWidth - drawnWidth) / 2f;
                float top = (pageHeight - drawnHeight) / 2f;

                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                canvas.drawBitmap(
                        bitmap,
                        null,
                        new RectF(left, top, left + drawnWidth, top + drawnHeight),
                        paint);
                document.finishPage(page);
                bitmap.recycle();
                pageNumber++;
            }

            File documentsRoot = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (documentsRoot == null) {
                throw new IOException("Stockage de documents indisponible");
            }
            File directory = new File(documentsRoot, "Scans");
            if (!directory.exists() && !directory.mkdirs()) {
                throw new IOException("Dossier PDF inaccessible");
            }
            String timestamp =
                    new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File pdf = new File(directory, "scan_" + timestamp + ".pdf");
            try (OutputStream output = new FileOutputStream(pdf)) {
                document.writeTo(output);
            }

            lastPdfFile = pdf;
            sharePdfButton.setEnabled(true);
            toast("PDF créé : " + pdfPages.size() + " page(s).");
        } catch (Exception e) {
            toast("Impossible de créer le PDF : " + safeMessage(e));
        } finally {
            document.close();
        }
    }

    private void sharePdf() {
        if (lastPdfFile == null || !lastPdfFile.exists()) {
            toast("Créez d’abord un PDF.");
            return;
        }

        Uri pdfUri = fileUri(lastPdfFile);
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("application/pdf");
        share.putExtra(Intent.EXTRA_STREAM, pdfUri);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(share, "Partager le PDF"));
    }

    private void resetSession() {
        pages.clear();
        currentImageUri = null;
        currentAddedToPages = false;
        lastPdfFile = null;
        preview.setImageDrawable(null);
        sharePdfButton.setEnabled(false);
        updatePageCount();
        toast("Nouvelle session prête.");
    }

    private void setCurrentImage(@NonNull Uri uri) {
        currentImageUri = uri;
        currentAddedToPages = false;
        preview.setImageURI(null);
        preview.setImageURI(uri);
    }

    private void updatePageCount() {
        pageCount.setText(getString(R.string.page_count, pages.size()));
    }

    private Uri copyUriToCache(@NonNull Uri source, @NonNull String prefix) throws IOException {
        File destination = createCacheFile(prefix, ".jpg");
        try (InputStream input = getContentResolver().openInputStream(source);
             OutputStream output = new FileOutputStream(destination)) {
            if (input == null) throw new IOException("Flux image indisponible");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
        return fileUri(destination);
    }

    private File createCacheFile(@NonNull String prefix, @NonNull String suffix) throws IOException {
        File directory = new File(getCacheDir(), "scans");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Cache inaccessible");
        }
        return File.createTempFile(prefix, suffix, directory);
    }

    private Uri fileUri(@NonNull File file) {
        return FileProvider.getUriForFile(
                this, getPackageName() + ".fileprovider", file);
    }

    @Nullable
    private Bitmap decodeBitmapForPdf(@NonNull Uri uri) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
            return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                int width = info.getSize().getWidth();
                int height = info.getSize().getHeight();
                int maxSide = Math.max(width, height);
                if (maxSide > 2400) {
                    float factor = 2400f / maxSide;
                    decoder.setTargetSize(
                            Math.max(1, Math.round(width * factor)),
                            Math.max(1, Math.round(height * factor)));
                }
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream first = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(first, null, bounds);
        }

        int sample = 1;
        while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > 2400) {
            sample *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        try (InputStream second = getContentResolver().openInputStream(uri)) {
            return BitmapFactory.decodeStream(second, null, options);
        }
    }

    private void registerNetworkCallback() {
        if (connectivityManager == null || networkCallbackRegistered) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.registerDefaultNetworkCallback(networkCallback);
            } else {
                NetworkRequest request = new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build();
                connectivityManager.registerNetworkCallback(request, networkCallback);
            }
            networkCallbackRegistered = true;
        } catch (RuntimeException ignored) {
            networkCallbackRegistered = false;
        }
        refreshNetworkStatus();
    }

    private void unregisterNetworkCallback() {
        if (connectivityManager == null || !networkCallbackRegistered) return;
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        } catch (RuntimeException ignored) {
            // Already unregistered by the platform.
        }
        networkCallbackRegistered = false;
    }

    private void refreshNetworkStatus() {
        runOnUiThread(() -> {
            if (networkStatus == null) return;
            networkStatus.setText(
                    isInternetValidated() ? R.string.network_online : R.string.network_offline);
        });
    }

    private boolean isInternetValidated() {
        if (connectivityManager == null) return false;
        Network active = connectivityManager.getActiveNetwork();
        if (active == null) return false;
        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(active);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private void toast(@NonNull String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private String safeMessage(@NonNull Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty()
                ? e.getClass().getSimpleName()
                : message;
    }
}
