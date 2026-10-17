package com.shaterguy.opentext;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.CharacterCodingException;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Single-document offline editor. Document access uses Android SAF rather than broad
 * filesystem permissions. No network or third-party runtime dependencies.
 */
public final class MainActivity extends Activity {
    private static final int REQUEST_OPEN = 201;
    private static final int REQUEST_SAVE_AS = 202;
    private static final int BLUE = Color.rgb(49, 93, 229);
    private static final int INK = Color.rgb(25, 35, 58);
    private static final int MUTED = Color.rgb(105, 117, 140);
    private static final int SURFACE = Color.rgb(247, 248, 252);

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private EditText editor;
    private TextView title;
    private TextView status;
    private Button newButton;
    private Button openButton;
    private Button saveButton;
    private Button saveAsButton;
    private boolean suppressEditorWatcher;
    private boolean dirty;
    private boolean busy;
    private boolean wrapLines = true;
    private boolean bom;
    private Uri currentUri;
    private String fileName = "새 문서.txt";
    private String encoding = "UTF-8";
    private String lastSearch = "";
    private Runnable afterSave;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        makeUi();
        if (state != null) {
            restoreState(state);
        } else {
            newDocument();
            openFromIntent(getIntent());
        }
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        confirmLeaving(() -> openFromIntent(intent));
    }

    private void makeUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(SURFACE);
        root.setFocusableInTouchMode(true);
        setContentView(root);

        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
                return insets;
            });
        }

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(18), dp(12), dp(18), dp(9));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        TextView brand = new TextView(this);
        brand.setText("OPEN TEXT   ·   오프라인 텍스트 편집기");
        brand.setTextSize(11);
        brand.setTextColor(BLUE);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(brand);

        title = new TextView(this);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(INK);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.topMargin = dp(6);
        header.addView(title, titleParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1, -2);
        actionParams.topMargin = dp(12);
        header.addView(actions, actionParams);
        newButton = makeAction("새 문서", false);
        openButton = makeAction("열기", false);
        saveButton = makeAction("저장", true);
        saveAsButton = makeAction("다른 이름", false);
        actions.addView(newButton, weightedAction());
        actions.addView(openButton, weightedAction());
        actions.addView(saveButton, weightedAction());
        LinearLayout.LayoutParams last = weightedAction();
        last.rightMargin = 0;
        actions.addView(saveAsButton, last);
        newButton.setOnClickListener(v -> confirmLeaving(this::newDocument));
        openButton.setOnClickListener(v -> confirmLeaving(this::pickDocument));
        saveButton.setOnClickListener(v -> saveDocument());
        saveAsButton.setOnClickListener(v -> saveAsDocument());

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams optionsParams = new LinearLayout.LayoutParams(-1, -2);
        optionsParams.topMargin = dp(6);
        header.addView(searchRow, optionsParams);

        TextView guide = new TextView(this);
        guide.setText("TXT · JSON · XML · YAML · MD · CSV · 코드 등");
        guide.setTextSize(11);
        guide.setTextColor(MUTED);
        searchRow.addView(guide, new LinearLayout.LayoutParams(0, -2, 1f));

        Button options = makeAction("도구 ⋮", false);
        options.setTextSize(12);
        searchRow.addView(options, new LinearLayout.LayoutParams(dp(85), dp(37)));
        options.setOnClickListener(v -> showTools(options));

        FrameLayout paper = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), Color.rgb(224, 228, 239));
        paper.setBackground(bg);
        paper.setElevation(dp(2));
        LinearLayout.LayoutParams paperParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        paperParams.setMargins(dp(12), dp(4), dp(12), dp(5));
        root.addView(paper, paperParams);

        editor = new EditText(this);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setTextSize(15);
        editor.setTextColor(INK);
        editor.setHintTextColor(MUTED);
        editor.setHint("문서를 열거나 새 텍스트를 입력하세요.");
        editor.setBackgroundColor(Color.TRANSPARENT);
        editor.setPadding(dp(14), dp(15), dp(14), dp(15));
        editor.setInputType(InputType.TYPE_CLASS_TEXT |
                InputType.TYPE_TEXT_FLAG_MULTI_LINE |
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        editor.setHorizontallyScrolling(false);
        editor.setSaveEnabled(false);
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!suppressEditorWatcher) {
                    dirty = true;
                    refreshTitle();
                    scheduleStatus();
                }
            }
            @Override public void afterTextChanged(Editable editable) {}
        });
        paper.addView(editor, new FrameLayout.LayoutParams(-1, -1));

        status = new TextView(this);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setTextSize(12);
        status.setTextColor(MUTED);
        status.setPadding(dp(17), dp(6), dp(17), dp(11));
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(35)));
    }

    private LinearLayout.LayoutParams weightedAction() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(43), 1f);
        params.rightMargin = dp(6);
        return params;
    }

    private Button makeAction(String text, boolean primary) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(12);
        button.setPadding(dp(2), 0, dp(2), 0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setTextColor(primary ? Color.WHITE : INK);
        button.setBackgroundTintList(ColorStateList.valueOf(primary ? BLUE : Color.rgb(231, 235, 247)));
        return button;
    }

    private int dp(float dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void newDocument() {
        currentUri = null;
        fileName = "새 문서.txt";
        encoding = "UTF-8";
        bom = false;
        lastSearch = "";
        suppressEditorWatcher = true;
        editor.setText("");
        suppressEditorWatcher = false;
        dirty = false;
        afterSave = null;
        refreshTitle();
        scheduleStatus();
        editor.clearFocus();
    }

    private void pickDocument() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, REQUEST_OPEN);
        } catch (Exception exception) {
            alert("파일 선택기를 열지 못했습니다", exception.getMessage());
        }
    }

    private void saveAsDocument() {
        if (busy) return;
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(mimeForName(fileName));
            intent.putExtra(Intent.EXTRA_TITLE, fileName);
            startActivityForResult(intent, REQUEST_SAVE_AS);
        } catch (Exception exception) {
            afterSave = null;
            alert("파일 생성기를 열지 못했습니다", exception.getMessage());
        }
    }

    private static String mimeForName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".json") || lower.endsWith(".jsonl")) return "application/json";
        if (lower.endsWith(".xml")) return "application/xml";
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "application/javascript";
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "text/markdown";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        return "text/plain";
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            if (requestCode == REQUEST_SAVE_AS) afterSave = null;
            return;
        }
        Uri uri = data.getData();
        takePermission(uri, data.getFlags());
        if (requestCode == REQUEST_OPEN) {
            openDocument(uri);
        } else if (requestCode == REQUEST_SAVE_AS) {
            saveToUri(uri, true);
        }
    }

    private void takePermission(Uri uri, int grantedFlags) {
        int modes = grantedFlags & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (modes == 0) return;
        try {
            getContentResolver().takePersistableUriPermission(uri, modes);
        } catch (SecurityException ignored) {
            // ACTION_VIEW grants can be temporary; read/write still works while active.
        }
    }

    private void openFromIntent(Intent intent) {
        if (intent == null || intent.getData() == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action) || Intent.ACTION_EDIT.equals(action)) {
            takePermission(intent.getData(), intent.getFlags());
            openDocument(intent.getData());
        }
    }

    private void openDocument(Uri uri) {
        if (busy) return;
        setBusy(true, "문서를 읽는 중입니다…");
        final String name = findName(uri);
        worker.execute(() -> {
            try {
                byte[] data = readBytes(uri);
                try {
                    TextFileCodec.Document parsed = TextFileCodec.decode(data, null);
                    runOnUiThread(() -> displayDocument(uri, name, parsed));
                } catch (CharacterCodingException | IllegalArgumentException encodingFailure) {
                    runOnUiThread(() -> chooseEncodingForOpen(uri, name, data));
                }
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    setBusy(false, null);
                    alert("문서를 열지 못했습니다", exception.getMessage());
                });
            }
        });
    }

    private static byte[] readBytes(Uri uri, android.content.ContentResolver resolver) throws IOException {
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IOException("파일을 읽을 수 없습니다.");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > TextFileCodec.MAX_FILE_BYTES) {
                    throw new IOException("8 MiB보다 큰 문서는 편집할 수 없습니다.");
                }
                bytes.write(buffer, 0, count);
            }
            return bytes.toByteArray();
        }
    }

    private byte[] readBytes(Uri uri) throws IOException {
        return readBytes(uri, getContentResolver());
    }

    private String findName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getString(0);
            }
        } catch (Exception ignored) { }
        String last = uri.getLastPathSegment();
        return last == null || last.isEmpty() ? "문서.txt" : last;
    }

    private void chooseEncodingForOpen(Uri uri, String name, byte[] data) {
        setBusy(false, null);
        new AlertDialog.Builder(this)
                .setTitle("문서의 문자 인코딩 선택")
                .setMessage("UTF-8로 읽지 못했습니다. 파일의 원래 인코딩을 선택하세요. 바이너리 파일은 편집할 수 없습니다.")
                .setItems(TextFileCodec.ENCODINGS, (dialog, selected) -> {
                    String chosen = TextFileCodec.ENCODINGS[selected];
                    setBusy(true, "인코딩을 확인하는 중입니다…");
                    worker.execute(() -> {
                        try {
                            TextFileCodec.Document parsed = TextFileCodec.decode(data, chosen);
                            runOnUiThread(() -> displayDocument(uri, name, parsed));
                        } catch (Exception exception) {
                            runOnUiThread(() -> {
                                setBusy(false, null);
                                alert("문서를 해독할 수 없습니다", exception.getMessage());
                            });
                        }
                    });
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void displayDocument(Uri uri, String name, TextFileCodec.Document doc) {
        currentUri = uri;
        fileName = name;
        encoding = doc.encoding;
        bom = doc.bom;
        suppressEditorWatcher = true;
        editor.setText(doc.text);
        editor.setSelection(0);
        suppressEditorWatcher = false;
        dirty = false;
        afterSave = null;
        setBusy(false, null);
        refreshTitle();
        scheduleStatus();
        editor.clearFocus();
    }

    private void saveDocument() {
        if (busy) return;
        if (currentUri == null) {
            saveAsDocument();
        } else {
            saveToUri(currentUri, false);
        }
    }

    private void saveToUri(Uri target, boolean newFile) {
        if (busy) return;
        final String snapshot = editor.getText().toString();
        final String savedEncoding = encoding;
        final boolean savedBom = bom;
        final String newName = newFile ? findName(target) : fileName;
        setBusy(true, "저장하는 중입니다…");
        worker.execute(() -> {
            try {
                byte[] encoded = TextFileCodec.encode(snapshot, savedEncoding, savedBom);
                if (encoded.length > TextFileCodec.MAX_FILE_BYTES) {
                    throw new IOException("저장할 문서가 8 MiB를 초과합니다.");
                }
                try (OutputStream output = getContentResolver().openOutputStream(target, "wt")) {
                    if (output == null) throw new IOException("파일 쓰기 권한이 없습니다.");
                    output.write(encoded);
                    output.flush();
                }
                byte[] verify = readBytes(target);
                if (!Arrays.equals(encoded, verify)) {
                    throw new IOException("저장 후 내용을 다시 읽어 비교했으나 일치하지 않습니다. 원본을 확인하세요.");
                }
                runOnUiThread(() -> {
                    if (newFile) {
                        currentUri = target;
                        fileName = newName;
                    }
                    if (snapshot.contentEquals(editor.getText()) &&
                            savedEncoding.equals(encoding) && savedBom == bom) {
                        dirty = false;
                    }
                    setBusy(false, null);
                    refreshTitle();
                    scheduleStatus();
                    Toast.makeText(this, "저장 완료", Toast.LENGTH_SHORT).show();
                    if (!dirty && afterSave != null) {
                        Runnable next = afterSave;
                        afterSave = null;
                        next.run();
                    }
                });
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    setBusy(false, null);
                    afterSave = null;
                    String message = exception instanceof CharacterCodingException
                            ? "현재 인코딩으로 표현할 수 없는 문자가 있습니다. 도구 > 저장 인코딩에서 UTF-8을 선택하세요."
                            : exception.getMessage();
                    alert("저장하지 못했습니다", message + "\n\n필요하면 '다른 이름'으로 저장하세요.");
                });
            }
        });
    }

    private void refreshTitle() {
        title.setText(fileName + (dirty ? "  ●" : ""));
        if (!busy && saveButton != null) saveButton.setEnabled(true);
    }

    private void scheduleStatus() {
        if (status == null || editor == null || busy) return;
        editor.removeCallbacks(statusUpdate);
        editor.postDelayed(statusUpdate, 150L);
    }

    private final Runnable statusUpdate = () -> {
        if (editor == null || status == null || busy) return;
        Editable text = editor.getText();
        int selection = Math.max(0, editor.getSelectionStart());
        int row = 1;
        int column = 1;
        for (int i = 0; i < Math.min(selection, text.length()); i++) {
            if (text.charAt(i) == '\n') { row++; column = 1; }
            else column++;
        }
        String lineEnd = TextFileCodec.lineEnding(text.toString());
        status.setText(String.format(Locale.KOREA,
                "%s%s  ·  %s  ·  %d행 %d열  ·  %,d자%s",
                encoding, bom ? " BOM" : "", lineEnd, row, column, text.length(),
                dirty ? "  ·  미저장" : ""));
    };

    private void setBusy(boolean enabled, String info) {
        busy = enabled;
        if (newButton != null) newButton.setEnabled(!enabled);
        if (openButton != null) openButton.setEnabled(!enabled);
        if (saveButton != null) saveButton.setEnabled(!enabled);
        if (saveAsButton != null) saveAsButton.setEnabled(!enabled);
        if (editor != null) editor.setEnabled(!enabled);
        if (enabled) status.setText(info == null ? "처리 중입니다…" : info);
        else scheduleStatus();
    }

    private void confirmLeaving(Runnable next) {
        if (busy) {
            Toast.makeText(this, "파일 작업이 완료된 후 다시 시도하세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!dirty) { next.run(); return; }
        new AlertDialog.Builder(this)
                .setTitle("저장하지 않은 변경사항")
                .setMessage("변경사항을 저장하지 않고 나가면 편집 내용이 사라집니다.")
                .setPositiveButton("저장 후 계속", (dialog, which) -> {
                    afterSave = next;
                    saveDocument();
                })
                .setNeutralButton("저장하지 않음", (dialog, which) -> {
                    afterSave = null;
                    next.run();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void showTools(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "텍스트 찾기");
        menu.getMenu().add(0, 2, 1, "줄 번호로 이동");
        menu.getMenu().add(0, 3, 2, wrapLines ? "자동 줄바꿈 끄기" : "자동 줄바꿈 켜기");
        menu.getMenu().add(0, 4, 3, "글자 크기");
        menu.getMenu().add(0, 5, 4, "저장 인코딩 선택");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: findText(); break;
                case 2: jumpToLine(); break;
                case 3: toggleWrap(); break;
                case 4: changeFontSize(); break;
                case 5: chooseSaveEncoding(); break;
                default: return false;
            }
            return true;
        });
        menu.show();
    }

    private void findText() {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setText(lastSearch);
        field.selectAll();
        field.setPadding(dp(18), dp(10), dp(18), dp(10));
        new AlertDialog.Builder(this)
                .setTitle("텍스트 찾기")
                .setView(field)
                .setPositiveButton("다음 찾기", (dialog, which) -> {
                    String query = field.getText().toString();
                    if (query.isEmpty()) return;
                    lastSearch = query;
                    String haystack = editor.getText().toString();
                    int from = Math.max(0, editor.getSelectionEnd());
                    int found = haystack.toLowerCase(Locale.ROOT)
                            .indexOf(query.toLowerCase(Locale.ROOT), from);
                    if (found < 0) found = haystack.toLowerCase(Locale.ROOT)
                            .indexOf(query.toLowerCase(Locale.ROOT), 0);
                    if (found < 0) {
                        Toast.makeText(this, "일치하는 텍스트가 없습니다.", Toast.LENGTH_SHORT).show();
                    } else {
                        editor.requestFocus();
                        editor.setSelection(found, Math.min(haystack.length(), found + query.length()));
                        scheduleStatus();
                    }
                })
                .setNegativeButton("취소", null).show();
    }

    private void jumpToLine() {
        EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setHint("이동할 줄 번호");
        field.setPadding(dp(18), dp(10), dp(18), dp(10));
        new AlertDialog.Builder(this)
                .setTitle("줄 번호로 이동")
                .setView(field)
                .setPositiveButton("이동", (dialog, which) -> {
                    try {
                        int target = Integer.parseInt(field.getText().toString());
                        if (target < 1) throw new NumberFormatException();
                        Editable chars = editor.getText();
                        int position = 0, row = 1;
                        while (position < chars.length() && row < target) {
                            if (chars.charAt(position) == '\n') row++;
                            position++;
                        }
                        editor.requestFocus();
                        editor.setSelection(position);
                        scheduleStatus();
                    } catch (NumberFormatException exception) {
                        Toast.makeText(this, "유효한 줄 번호를 입력하세요.", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("취소", null).show();
    }

    private void toggleWrap() {
        wrapLines = !wrapLines;
        editor.setHorizontallyScrolling(!wrapLines);
        editor.setSingleLine(false);
        Toast.makeText(this, wrapLines ? "자동 줄바꿈 켜짐" : "자동 줄바꿈 꺼짐", Toast.LENGTH_SHORT).show();
    }

    private void changeFontSize() {
        final String[] names = {"12sp", "14sp", "15sp", "17sp", "19sp", "22sp"};
        final int[] sizes = {12, 14, 15, 17, 19, 22};
        new AlertDialog.Builder(this)
                .setTitle("편집기 글자 크기")
                .setItems(names, (dialog, which) -> editor.setTextSize(sizes[which]))
                .show();
    }

    private void chooseSaveEncoding() {
        new AlertDialog.Builder(this)
                .setTitle("저장 인코딩")
                .setMessage("저장 시 적용할 인코딩입니다. 원본을 다시 해독하려면 파일을 다시 여세요.")
                .setSingleChoiceItems(TextFileCodec.ENCODINGS, encodingIndex(), (dialog, which) -> {
                    String next = TextFileCodec.ENCODINGS[which];
                    if (!next.equals(encoding)) {
                        encoding = next;
                        bom = next.startsWith("UTF-16");
                        dirty = true;
                        refreshTitle();
                        scheduleStatus();
                    }
                    dialog.dismiss();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private int encodingIndex() {
        for (int i = 0; i < TextFileCodec.ENCODINGS.length; i++) {
            if (TextFileCodec.ENCODINGS[i].equals(encoding)) return i;
        }
        return 0;
    }

    private void alert(String header, String details) {
        new AlertDialog.Builder(this)
                .setTitle(header)
                .setMessage(details == null || details.isEmpty() ? "알 수 없는 오류입니다." : details)
                .setPositiveButton("확인", null)
                .show();
    }

    @Override
    public void onBackPressed() {
        confirmLeaving(() -> MainActivity.super.onBackPressed());
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("uri", currentUri == null ? null : currentUri.toString());
        out.putString("name", fileName);
        out.putString("encoding", encoding);
        out.putBoolean("bom", bom);
        out.putBoolean("dirty", dirty);
        out.putBoolean("wrap", wrapLines);
        out.putString("search", lastSearch);
        // Large editor contents cannot be put into Binder state safely.
        String text = editor.getText().toString();
        if (text.length() < 100_000) {
            out.putString("text", text);
        } else {
            try (java.io.Writer writer = new java.io.OutputStreamWriter(
                    openFileOutput("draft-state.txt", MODE_PRIVATE), java.nio.charset.StandardCharsets.UTF_8)) {
                writer.write(text);
                out.putString("draft", "draft-state.txt");
            } catch (IOException ignored) { }
        }
    }

    private void restoreState(Bundle state) {
        String savedUri = state.getString("uri");
        currentUri = savedUri == null ? null : Uri.parse(savedUri);
        fileName = state.getString("name", "새 문서.txt");
        encoding = state.getString("encoding", "UTF-8");
        bom = state.getBoolean("bom", false);
        dirty = state.getBoolean("dirty", false);
        wrapLines = state.getBoolean("wrap", true);
        lastSearch = state.getString("search", "");
        String recovered = state.getString("text");
        if (recovered == null && state.containsKey("draft")) {
            try (InputStream input = openFileInput("draft-state.txt")) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = input.read(buffer)) > 0) out.write(buffer, 0, n);
                recovered = out.toString("UTF-8");
            } catch (IOException ignored) { }
        }
        suppressEditorWatcher = true;
        editor.setText(recovered == null ? "" : recovered);
        editor.setHorizontallyScrolling(!wrapLines);
        suppressEditorWatcher = false;
        refreshTitle();
        scheduleStatus();
        editor.clearFocus();
    }

    @Override
    protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }
}
