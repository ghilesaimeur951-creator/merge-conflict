package com.mergeconflict.scanner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
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
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private ImageView preview;
    private TextView networkStatus;
    private TextView pageCount;
    private TextView scanStatus;
    private TextView historyEmpty;
    private LinearLayout historyContainer;
    private ProgressBar scanProgress;
    private Button sharePdfButton;
    private Button shareSelectionButton;
    private Button deleteSelectionButton;

    @Nullable private Uri currentImageUri;
    @Nullable private Uri pendingCameraUri;
    @Nullable private File lastPdfFile;
    private boolean currentAddedToPages = false;

    private final List<Uri> pages = new ArrayList<>();
    private final Set<File> selectedHistoryFiles = new HashSet<>();
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    @Nullable private ConnectivityManager connectivityManager;
    private boolean networkCallbackRegistered = false;

    private static final class AutoCropResult {
        final Uri original;
        final Uri output;
        @Nullable final DocumentEdgeDetector.CropProposal proposal;

        AutoCropResult(
                @NonNull Uri original,
                @NonNull Uri output,
                @Nullable DocumentEdgeDetector.CropProposal proposal) {
            this.original = original;
            this.output = output;
            this.proposal = proposal;
        }

        boolean detected() {
            return proposal != null && !original.equals(output);
        }
    }

    private final ActivityResultLauncher<Intent> cameraLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && pendingCameraUri != null) {
                    prepareSingleImage(pendingCameraUri);
                } else if (result.getResultCode() != Activity.RESULT_CANCELED) {
                    toast("La prise de photo n’a pas abouti.");
                }
                pendingCameraUri = null;
            });

    private final ActivityResultLauncher<String> importLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri == null) return;
                setBusy(true, "Import de l’image…");
                ioExecutor.execute(() -> {
                    try {
                        Uri localCopy = copyUriToCache(uri, "import_");
                        runOnUiThread(() -> prepareSingleImage(localCopy));
                    } catch (IOException e) {
                        runOnUiThread(() -> {
                            setBusy(false, "Import impossible.");
                            toast("Impossible d’importer cette image.");
                        });
                    }
                });
            });

    private final ActivityResultLauncher<String[]> bulkImportLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                if (uris == null || uris.isEmpty()) return;
                importMultipleImages(uris);
            });

    private final ActivityResultLauncher<Intent> cropLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                Intent data = result.getData();
                if (result.getResultCode() == Activity.RESULT_OK && data != null) {
                    Uri output = UCrop.getOutput(data);
                    if (output != null) {
                        setCurrentImage(output);
                        scanStatus.setText("Rognage manuel appliqué. L’image est prête.");
                        toast("Image rognée.");
                    }
                } else if (result.getResultCode() == UCrop.RESULT_ERROR && data != null) {
                    Throwable error = UCrop.getError(data);
                    toast(error != null
                            ? "Erreur de rognage : " + error.getMessage()
                            : "Erreur de rognage.");
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
                public void onCapabilitiesChanged(
                        @NonNull Network network,
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
        scanStatus = findViewById(R.id.scanStatus);
        historyEmpty = findViewById(R.id.historyEmpty);
        historyContainer = findViewById(R.id.historyContainer);
        scanProgress = findViewById(R.id.scanProgress);
        sharePdfButton = findViewById(R.id.sharePdfButton);
        shareSelectionButton = findViewById(R.id.shareSelectionButton);
        deleteSelectionButton = findViewById(R.id.deleteSelectionButton);

        connectivityManager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        findViewById(R.id.takePhotoButton).setOnClickListener(v -> takePhoto());
        findViewById(R.id.importButton).setOnClickListener(v -> importLauncher.launch("image/*"));
        findViewById(R.id.importMultipleButton)
                .setOnClickListener(v -> bulkImportLauncher.launch(new String[]{"image/*"}));
        findViewById(R.id.cropButton).setOnClickListener(v -> cropCurrentImage());
        findViewById(R.id.addPageButton).setOnClickListener(v -> addCurrentPage());
        findViewById(R.id.createPdfButton).setOnClickListener(v -> createPdf());
        sharePdfButton.setOnClickListener(v -> shareLatestPdf());
        findViewById(R.id.resetButton).setOnClickListener(v -> resetSession());
        shareSelectionButton.setOnClickListener(v -> shareSelectedHistory());
        deleteSelectionButton.setOnClickListener(v -> confirmDeleteSelected());

        updatePageCount();
        updateHistorySelectionControls();
        refreshHistory();
        refreshNetworkStatus();
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerNetworkCallback();
        refreshHistory();
    }

    @Override
    protected void onStop() {
        unregisterNetworkCallback();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    private void takePhoto() {
        try {
            File imageFile = createCacheFile("camera_", ".jpg");
            pendingCameraUri = fileUri(imageFile);

            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, pendingCameraUri);
            intent.addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

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

    private void prepareSingleImage(@NonNull Uri source) {
        setCurrentImage(source);
        setBusy(true, "Détection automatique des bords de la feuille…");

        ioExecutor.execute(() -> {
            try {
                AutoCropResult result = detectAndCreateAutomaticCrop(source);
                runOnUiThread(() -> {
                    setBusy(false, result.detected()
                            ? "Une proposition de rognage est prête."
                            : "Bords incertains : rognage manuel conseillé.");
                    if (result.detected()) {
                        showCropProposalDialog(result);
                    } else {
                        showNoAutomaticCropDialog(source);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false, "Analyse terminée : rognage manuel disponible.");
                    showNoAutomaticCropDialog(source);
                });
            }
        });
    }

    private void showCropProposalDialog(@NonNull AutoCropResult result) {
        View content = getLayoutInflater().inflate(R.layout.dialog_crop_proposal, null);
        ImageView cropPreview = content.findViewById(R.id.cropProposalPreview);
        TextView details = content.findViewById(R.id.cropProposalDetails);
        cropPreview.setImageURI(result.output);

        DocumentEdgeDetector.CropProposal proposal = result.proposal;
        if (proposal != null) {
            details.setText(String.format(
                    Locale.FRANCE,
                    "Zone détectée : %.0f %% × %.0f %% de l’image\n%s • confiance %d %%\n\nLe calcul est effectué entièrement sur le téléphone.",
                    proposal.widthFraction() * 100f,
                    proposal.heightFraction() * 100f,
                    proposal.formatLabel(),
                    proposal.confidence));
        }

        new AlertDialog.Builder(this)
                .setTitle("Rognage automatique proposé")
                .setView(content)
                .setPositiveButton("Accepter", (dialog, which) -> {
                    setCurrentImage(result.output);
                    scanStatus.setText("Rognage automatique accepté. Document prêt.");
                })
                .setNeutralButton("Rogner moi-même", (dialog, which) -> {
                    setCurrentImage(result.original);
                    startManualCrop(result.original);
                })
                .setNegativeButton("Garder l’original", (dialog, which) -> {
                    setCurrentImage(result.original);
                    scanStatus.setText("Image originale conservée.");
                })
                .show();
    }

    private void showNoAutomaticCropDialog(@NonNull Uri source) {
        new AlertDialog.Builder(this)
                .setTitle("Bords difficiles à détecter")
                .setMessage("Le scanner n’a pas trouvé un cadrage suffisamment fiable. "
                        + "Vous pouvez rogner l’image vous-même ou conserver l’original.")
                .setPositiveButton("Rogner moi-même",
                        (dialog, which) -> startManualCrop(source))
                .setNegativeButton("Garder l’original",
                        (dialog, which) -> {
                            setCurrentImage(source);
                            scanStatus.setText("Image originale conservée.");
                        })
                .show();
    }

    private void cropCurrentImage() {
        if (currentImageUri == null) {
            toast("Prenez ou importez d’abord une photo.");
            return;
        }
        startManualCrop(currentImageUri);
    }

    private void startManualCrop(@NonNull Uri source) {
        try {
            File outputFile = createCacheFile("crop_", ".jpg");
            Uri destination = fileUri(outputFile);

            UCrop.Options options = new UCrop.Options();
            options.setFreeStyleCropEnabled(true);
            options.setCompressionFormat(Bitmap.CompressFormat.JPEG);
            options.setCompressionQuality(94);
            options.setHideBottomControls(false);
            options.setToolbarTitle("Ajuster les bords");
            options.setToolbarColor(getColor(R.color.ink));
            options.setStatusBarColor(getColor(R.color.ink));
            options.setActiveControlsWidgetColor(getColor(R.color.accent));

            Intent cropIntent = UCrop.of(source, destination)
                    .withOptions(options)
                    .getIntent(this);
            cropIntent.addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            cropLauncher.launch(cropIntent);
        } catch (IOException e) {
            toast("Impossible de préparer le rognage.");
        }
    }

    private void importMultipleImages(@NonNull List<Uri> uris) {
        setBusy(true, "Analyse de " + uris.size() + " images…");
        ioExecutor.execute(() -> {
            List<Uri> originals = new ArrayList<>();
            List<Uri> proposals = new ArrayList<>();
            int detected = 0;

            for (Uri uri : uris) {
                try {
                    Uri local = copyUriToCache(uri, "bulk_");
                    originals.add(local);
                    AutoCropResult result = detectAndCreateAutomaticCrop(local);
                    proposals.add(result.output);
                    if (result.detected()) detected++;
                } catch (Exception ignored) {
                    // Skip unreadable images and continue with the rest.
                }
            }

            int detectedCount = detected;
            runOnUiThread(() -> {
                setBusy(false, "Import multiple prêt.");
                if (originals.isEmpty()) {
                    toast("Aucune image exploitable n’a été importée.");
                    return;
                }
                showBulkImportChoice(originals, proposals, detectedCount);
            });
        });
    }

    private void showBulkImportChoice(
            @NonNull List<Uri> originals,
            @NonNull List<Uri> proposals,
            int detectedCount) {

        String message = originals.size() + " image(s) importée(s).\n"
                + detectedCount + " cadrage(s) automatique(s) détecté(s).\n\n"
                + "Vous pouvez appliquer les cadrages détectés à tout le lot ou garder les originaux.";

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Créer un PDF à partir de plusieurs images")
                .setMessage(message)
                .setNegativeButton("Annuler", null)
                .setNeutralButton("Garder les originaux",
                        (dialog, which) -> addBulkPages(originals));

        if (detectedCount > 0) {
            builder.setPositiveButton("Appliquer les rognages",
                    (dialog, which) -> addBulkPages(proposals));
        } else {
            builder.setPositiveButton("Ajouter les images",
                    (dialog, which) -> addBulkPages(originals));
        }
        builder.show();
    }

    private void addBulkPages(@NonNull List<Uri> importedPages) {
        pages.addAll(importedPages);
        Uri last = importedPages.get(importedPages.size() - 1);
        currentImageUri = last;
        currentAddedToPages = true;
        preview.setImageURI(null);
        preview.setImageURI(last);
        updatePageCount();
        scanStatus.setText(importedPages.size()
                + " image(s) ajoutée(s) au lot. Vous pouvez créer le PDF directement.");
        toast("Lot ajouté au PDF.");
    }

    private AutoCropResult detectAndCreateAutomaticCrop(@NonNull Uri source) throws IOException {
        Bitmap sample = decodeBitmapMaxSide(source, 900);
        if (sample == null) {
            return new AutoCropResult(source, source, null);
        }

        DocumentEdgeDetector.CropProposal proposal = DocumentEdgeDetector.detect(sample);
        sample.recycle();

        if (proposal == null) {
            return new AutoCropResult(source, source, null);
        }

        Uri cropped = createAutomaticCrop(source, proposal);
        return new AutoCropResult(source, cropped, proposal);
    }

    private Uri createAutomaticCrop(
            @NonNull Uri source,
            @NonNull DocumentEdgeDetector.CropProposal proposal) throws IOException {

        Bitmap bitmap = decodeBitmapMaxSide(source, 3000);
        if (bitmap == null) throw new IOException("Image illisible");

        int left = clamp(Math.round(proposal.left * bitmap.getWidth()), 0, bitmap.getWidth() - 2);
        int top = clamp(Math.round(proposal.top * bitmap.getHeight()), 0, bitmap.getHeight() - 2);
        int right = clamp(Math.round(proposal.right * bitmap.getWidth()), left + 1, bitmap.getWidth());
        int bottom = clamp(Math.round(proposal.bottom * bitmap.getHeight()), top + 1, bitmap.getHeight());

        Bitmap cropped = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top);
        File destination = createCacheFile("auto_crop_", ".jpg");

        try (OutputStream output = new FileOutputStream(destination)) {
            if (!cropped.compress(Bitmap.CompressFormat.JPEG, 94, output)) {
                throw new IOException("Compression impossible");
            }
        } finally {
            if (cropped != bitmap) cropped.recycle();
            bitmap.recycle();
        }
        return fileUri(destination);
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
            scanStatus.setText("Page ajoutée. Scannez ou importez la suivante.");
            toast("Page ajoutée.");
        } else {
            toast("Cette image est déjà dans le lot.");
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

        setBusy(true, "Création du PDF de " + pdfPages.size() + " page(s)…");
        ioExecutor.execute(() -> {
            try {
                File pdf = buildPdf(pdfPages);
                runOnUiThread(() -> {
                    lastPdfFile = pdf;
                    sharePdfButton.setEnabled(true);
                    setBusy(false, "PDF créé et enregistré dans la bibliothèque.");
                    refreshHistory();
                    toast("PDF créé : " + pdfPages.size() + " page(s).");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false, "La création du PDF a échoué.");
                    toast("Impossible de créer le PDF : " + safeMessage(e));
                });
            }
        });
    }

    private File buildPdf(@NonNull List<Uri> pdfPages) throws IOException {
        PdfDocument document = new PdfDocument();
        try {
            int pageNumber = 1;
            for (Uri imageUri : pdfPages) {
                Bitmap bitmap = decodeBitmapMaxSide(imageUri, 2600);
                if (bitmap == null) throw new IOException("Image illisible");

                boolean landscape = bitmap.getWidth() > bitmap.getHeight();
                int pageWidth = landscape ? 842 : 595;
                int pageHeight = landscape ? 595 : 842;

                PdfDocument.PageInfo info =
                        new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create();
                PdfDocument.Page page = document.startPage(info);
                Canvas canvas = page.getCanvas();
                canvas.drawColor(android.graphics.Color.WHITE);

                float margin = 22f;
                float availableWidth = pageWidth - (margin * 2f);
                float availableHeight = pageHeight - (margin * 2f);
                float scale =
                        Math.min(availableWidth / bitmap.getWidth(),
                                availableHeight / bitmap.getHeight());

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

            File directory = getScanDirectory(true);
            if (directory == null) throw new IOException("Stockage de documents indisponible");

            String timestamp =
                    new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File pdf = new File(directory, "scan_" + timestamp + ".pdf");

            try (OutputStream output = new FileOutputStream(pdf)) {
                document.writeTo(output);
            }
            return pdf;
        } finally {
            document.close();
        }
    }

    private void shareLatestPdf() {
        if (lastPdfFile == null || !lastPdfFile.exists()) {
            toast("Créez d’abord un PDF.");
            return;
        }
        sharePdfFiles(Arrays.asList(lastPdfFile));
    }

    private void shareSelectedHistory() {
        if (selectedHistoryFiles.isEmpty()) {
            toast("Sélectionnez au moins un PDF.");
            return;
        }
        sharePdfFiles(new ArrayList<>(selectedHistoryFiles));
    }

    private void sharePdfFiles(@NonNull List<File> files) {
        ArrayList<Uri> uris = new ArrayList<>();
        for (File file : files) {
            if (file.exists()) uris.add(fileUri(file));
        }
        if (uris.isEmpty()) {
            toast("Aucun PDF disponible.");
            return;
        }

        Intent share;
        if (uris.size() == 1) {
            share = new Intent(Intent.ACTION_SEND);
            share.setType("application/pdf");
            share.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            share = new Intent(Intent.ACTION_SEND_MULTIPLE);
            share.setType("application/pdf");
            share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }

        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ClipData clipData =
                new ClipData("PDF", new String[]{"application/pdf"}, new ClipData.Item(uris.get(0)));
        for (int i = 1; i < uris.size(); i++) {
            clipData.addItem(new ClipData.Item(uris.get(i)));
        }
        share.setClipData(clipData);

        startActivity(Intent.createChooser(
                share,
                uris.size() == 1 ? "Partager le PDF" : "Partager les PDF"));
    }

    private void refreshHistory() {
        if (historyContainer == null) return;

        historyContainer.removeAllViews();
        selectedHistoryFiles.clear();
        updateHistorySelectionControls();

        File directory = getScanDirectory(false);
        File[] pdfs = directory == null
                ? null
                : directory.listFiles((dir, name) ->
                        name != null && name.toLowerCase(Locale.ROOT).endsWith(".pdf"));

        if (pdfs == null || pdfs.length == 0) {
            historyEmpty.setVisibility(View.VISIBLE);
            return;
        }

        historyEmpty.setVisibility(View.GONE);
        Arrays.sort(pdfs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

        for (File file : pdfs) {
            View row = getLayoutInflater().inflate(R.layout.item_pdf, historyContainer, false);
            CheckBox checkbox = row.findViewById(R.id.pdfCheckBox);
            TextView title = row.findViewById(R.id.pdfTitle);
            TextView meta = row.findViewById(R.id.pdfMeta);
            Button open = row.findViewById(R.id.openPdfButton);
            Button share = row.findViewById(R.id.shareOnePdfButton);

            title.setText(file.getName());
            meta.setText(formatFileMeta(file));

            checkbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) {
                    selectedHistoryFiles.add(file);
                } else {
                    selectedHistoryFiles.remove(file);
                }
                updateHistorySelectionControls();
            });

            open.setOnClickListener(v -> openPdf(file));
            share.setOnClickListener(v -> sharePdfFiles(Arrays.asList(file)));
            row.setOnClickListener(v -> checkbox.setChecked(!checkbox.isChecked()));

            historyContainer.addView(row);
        }
    }

    private void openPdf(@NonNull File file) {
        Uri uri = fileUri(file);
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(uri, "application/pdf");
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(view);
        } catch (ActivityNotFoundException e) {
            toast("Aucune application ne peut ouvrir ce PDF.");
        }
    }

    private void confirmDeleteSelected() {
        if (selectedHistoryFiles.isEmpty()) {
            toast("Sélectionnez au moins un PDF.");
            return;
        }

        int count = selectedHistoryFiles.size();
        new AlertDialog.Builder(this)
                .setTitle("Supprimer " + count + " PDF ?")
                .setMessage("Cette suppression retire définitivement les fichiers du stockage de Pocket Scanner.")
                .setPositiveButton("Supprimer", (dialog, which) -> {
                    List<File> copy = new ArrayList<>(selectedHistoryFiles);
                    int deleted = 0;
                    for (File file : copy) {
                        if (file.delete()) deleted++;
                    }
                    refreshHistory();
                    toast(deleted + " PDF supprimé(s).");
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void updateHistorySelectionControls() {
        if (shareSelectionButton == null || deleteSelectionButton == null) return;
        int count = selectedHistoryFiles.size();
        shareSelectionButton.setEnabled(count > 0);
        deleteSelectionButton.setEnabled(count > 0);
        shareSelectionButton.setText(count > 0
                ? "Partager la sélection (" + count + ")"
                : "Partager la sélection");
    }

    private void resetSession() {
        pages.clear();
        currentImageUri = null;
        currentAddedToPages = false;
        lastPdfFile = null;
        preview.setImageDrawable(null);
        sharePdfButton.setEnabled(false);
        updatePageCount();
        scanStatus.setText("Nouvelle session prête. Les PDF déjà créés restent dans la bibliothèque.");
        toast("Nouvelle session prête.");
    }

    private void setCurrentImage(@NonNull Uri uri) {
        currentImageUri = uri;
        currentAddedToPages = false;
        preview.setImageURI(null);
        preview.setImageURI(uri);
        updatePageCount();
    }

    private void updatePageCount() {
        int effective = pages.size()
                + ((currentImageUri != null && !currentAddedToPages) ? 1 : 0);
        pageCount.setText(getString(R.string.page_count, effective));
    }

    private void setBusy(boolean busy, @NonNull String message) {
        if (scanProgress != null) {
            scanProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
        }
        if (scanStatus != null) {
            scanStatus.setText(message);
        }
    }

    private Uri copyUriToCache(@NonNull Uri source, @NonNull String prefix) throws IOException {
        File destination = createCacheFile(prefix, ".img");
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

    @Nullable
    private File getScanDirectory(boolean create) {
        File documentsRoot = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (documentsRoot == null) return null;
        File directory = new File(documentsRoot, "Scans");
        if (create && !directory.exists() && !directory.mkdirs()) return null;
        return directory;
    }

    private Uri fileUri(@NonNull File file) {
        return FileProvider.getUriForFile(
                this, getPackageName() + ".fileprovider", file);
    }

    @Nullable
    private Bitmap decodeBitmapMaxSide(@NonNull Uri uri, int maxSide) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
            return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                int width = info.getSize().getWidth();
                int height = info.getSize().getHeight();
                int longest = Math.max(width, height);
                if (longest > maxSide) {
                    float factor = maxSide / (float) longest;
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
            if (first == null) return null;
            BitmapFactory.decodeStream(first, null, bounds);
        }

        int sample = 1;
        while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > maxSide) {
            sample *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        try (InputStream second = getContentResolver().openInputStream(uri)) {
            if (second == null) return null;
            return BitmapFactory.decodeStream(second, null, options);
        }
    }

    private String formatFileMeta(@NonNull File file) {
        String date =
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(new Date(file.lastModified()));
        return date + " • " + humanFileSize(file.length());
    }

    private String humanFileSize(long bytes) {
        if (bytes < 1024) return bytes + " o";
        double kb = bytes / 1024d;
        if (kb < 1024d) return String.format(Locale.FRANCE, "%.0f Ko", kb);
        return String.format(Locale.FRANCE, "%.1f Mo", kb / 1024d);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
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
            boolean online = isInternetValidated();
            networkStatus.setText(
                    online ? R.string.network_online : R.string.network_offline);
            networkStatus.setTextColor(
                    getColor(online ? R.color.online_text : R.color.offline_text));
            networkStatus.setBackgroundResource(
                    online ? R.drawable.bg_chip_online : R.drawable.bg_chip_offline);
        });
    }

    private boolean isInternetValidated() {
        if (connectivityManager == null) return false;
        Network active = connectivityManager.getActiveNetwork();
        if (active == null) return false;
        NetworkCapabilities capabilities =
                connectivityManager.getNetworkCapabilities(active);
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
