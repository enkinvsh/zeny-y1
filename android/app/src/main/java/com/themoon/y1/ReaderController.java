package com.themoon.y1;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import com.themoon.y1.reader.BookIndex;
import com.themoon.y1.reader.BookLibrary;
import com.themoon.y1.reader.EpubReader;
import com.themoon.y1.reader.Fb2Reader;
import com.themoon.y1.reader.LibraryEntry;
import com.themoon.y1.reader.Progress;
import com.themoon.y1.reader.ReaderEngine;
import com.themoon.y1.reader.ReaderFormat;
import com.themoon.y1.reader.ReaderPositions;
import com.themoon.y1.reader.ResumePolicy;
import com.themoon.y1.views.BookListView;
import com.themoon.y1.views.RsvpView;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Читалка RSVP: список книг с карты и экран чтения по одному слову. MainActivity держит только
 * состояния экрана и маршрутизацию клавиш, всё остальное (поиск книг, индекс, тик чтения, позиции,
 * меню) здесь. Музыка играет сама по себе, читалка её не трогает.
 *
 * Всё, что делает UI, вызывается с главного потока. Фоновые потоки (поиск книг, сборка индекса,
 * обложка) возвращают результат через {@link #ui} и сверяют поколение: ушедший с экрана результат
 * молча выбрасывается.
 */
public final class ReaderController implements BookListView.Listener {

    public interface MenuListener {
        void onPick(int index);
    }

    /** Всё, чего контроллер не может сделать сам: экраны, звук клика, диалог выбора. */
    public interface Host {
        String t(String key);

        void click();

        void toast(String text);

        /** true: перейти на экран чтения, false: на список книг. */
        void goTo(boolean reading);

        /** Назад из списка: туда, откуда пришли. */
        void leave();

        /** Сбросить таймер подсветки: ключи при чтении не нажимаются. */
        void keepAwake();

        void showMenu(String[] labels, int focusIndex, MenuListener listener);
    }

    private static final String PREF_WPM = "reader_wpm";
    private static final String PREF_WORD_PX = "reader_word_px";
    private static final String PREF_ORDER = "reader_order";
    private static final int DEFAULT_WPM = 300;
    /** Пока подсветка не гасла: подсказка системе раз в столько мс, пока идёт чтение. */
    private static final long KEEP_AWAKE_MS = 5000;
    /** Дольше этого NEXT/PREV уже не касание, а удержание (перемотка). */
    private static final long HOLD_START_MS = 400;
    /** Обложка подгружается, когда выбор простоял столько мс: быстрый поворот колеса ничего не декодирует. */
    private static final long COVER_DEBOUNCE_MS = 180;
    /** Длинная сторона обложки после уменьшения: хватает на контейнер 146x219 с запасом. */
    private static final int COVER_LONG_SIDE = 300;
    /** Сообщение читалок EPUB/FB2 о книге без текста: по нему различаем «нет текста» и «не открылась». */
    private static final String ERR_NO_TEXT = "В книге нет текста";

    private static final int ACT_CONTENTS = 0;
    private static final int ACT_MARKS = 1;
    private static final int ACT_ADD_MARK = 2;
    private static final int ACT_RETURN = 3;
    private static final int ACT_SPEED = 4;
    private static final int ACT_SIZE = 5;
    private static final int ACT_EXIT = 6;

    private final Host host;
    private final SharedPreferences prefs;
    private final BookListView list;
    private final RsvpView rsvp;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final File booksDir = new File(Y1Paths.SD, "Books");
    private final File cacheDir = new File(booksDir, ".cache");
    private final ReaderPositions positions = new ReaderPositions(new File(cacheDir, "positions.json"));
    private final BookLibrary library = new BookLibrary();
    private volatile boolean positionsLoaded;

    private List<LibraryEntry> books = new ArrayList<LibraryEntry>();
    private int order;
    private boolean active;
    private boolean reading;
    private boolean returningFromBook;

    // Поколения: у каждого фонового дела свой счётчик, увеличение отменяет прежнее.
    private volatile int scanGen;
    private volatile int openGen;
    private volatile int coverGen;
    private boolean opening;
    private volatile int buildPct;
    private volatile boolean progressPending;

    // Открытая книга: индекс держим только пока она открыта.
    private BookIndex index;
    private ReaderEngine engine;
    private String bookId;
    private LibraryEntry current;
    private int shownChapter = -1;
    /** Читатель что-то двигал или читал: только тогда позиция записывается (иначе открыл и вышел не меняет «последнюю»). */
    private boolean touched;
    private long pausedAt;
    private boolean movedWhilePaused;
    private long lastKeepAwake;
    private long lastWheelAt;

    // Клавиши NEXT/PREV: момент нажатия, шаг перемотки и слово, с которого она началась.
    private long seekDownAt;
    private long lastSeekStep;
    private boolean seekActive;
    private int seekStartWord;

    // Обложка: в памяти одна картинка, для выбранной книги.
    private LibraryEntry coverEntry;
    private Bitmap coverBitmap;
    private boolean coverReady;
    private LibraryEntry coverPending;
    private Bitmap robot;

    // Один Runnable на тик чтения и по одному на остальные периодические дела: новых объектов на слово нет.
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            onTick();
        }
    };
    private final Runnable coverLoad = new Runnable() {
        @Override
        public void run() {
            loadCoverNow();
        }
    };
    private final Runnable progressShow = new Runnable() {
        @Override
        public void run() {
            progressPending = false;
            if (opening && list != null) {
                int p = buildPct;
                list.setBusy(host.t("Preparing the book") + " " + p + "%", p);
            }
        }
    };

    public ReaderController(Host host, SharedPreferences prefs, BookListView list, RsvpView rsvp) {
        this.host = host;
        this.prefs = prefs;
        this.list = list;
        this.rsvp = rsvp;
        this.order = prefs.getInt(PREF_ORDER, BookLibrary.ORDER_RECENT);
        list.setListener(this);
    }

    // ------------------------------------------------------------------ вход и выход экранов

    /** Экран списка книг. С чтения возвращаемся без нового поиска: список тот же, у одной книги новое состояние. */
    public void showLibrary() {
        active = true;
        boolean back = returningFromBook;
        returningFromBook = false;
        reading = false;
        rsvp.setVisibility(View.GONE);
        list.setVisibility(View.VISIBLE);
        if (back && !books.isEmpty()) {
            LibraryEntry keep = current;
            if (keep != null) {
                BookLibrary.applyState(keep, positions);
                BookLibrary.sort(books, order);
                list.setBooks(books, Math.max(0, books.indexOf(keep)));
            } else {
                list.setBooks(books, 0);
            }
            return;
        }
        list.setTypefaces(ThemeManager.getCustomFontTitle(), ThemeManager.getCustomFontBold(),
                ThemeManager.getCustomFont());
        list.setNewLabel(host.t("New"));
        startScan();
    }

    /** Экран чтения уже готов к показу (книга открыта в startReading). */
    public void showReading() {
        active = true;
        reading = true;
        list.setVisibility(View.GONE);
        rsvp.setVisibility(View.VISIBLE);
    }

    /** Любой другой экран приложения: всё освободить, позиции сбросить на карту. */
    public void leave() {
        if (!active) return;
        active = false;
        closeBook();
        returningFromBook = false;
        scanGen++;
        openGen++;
        coverGen++;
        opening = false;
        ui.removeCallbacks(coverLoad);
        positions.flushNow();
        books = new ArrayList<LibraryEntry>();
        list.setBooks(null, 0);
        list.setCover(null);
        list.setPlaceholder(null);
        coverEntry = null;
        coverBitmap = null;
        coverReady = false;
        coverPending = null;
    }

    public void onActivityPause() {
        if (active && reading && engine != null) pauseReading();
        positions.flushNow();
    }

    public void onScreenOff() {
        onActivityPause();
    }

    // ------------------------------------------------------------------ библиотека

    private void startScan() {
        final int gen = ++scanGen;
        if (books.isEmpty()) list.setBusy(host.t("Loading"), -1);
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (!positionsLoaded) {
                    positions.load();
                    positionsLoaded = true;
                }
                // Папку заводим сами: человек видит, куда класть книги, и загрузка с компьютера её находит.
                if (!booksDir.isDirectory()) booksDir.mkdirs();
                final List<LibraryEntry> found = library.scan(booksDir, positions, new Progress() {
                    @Override
                    public void onProgress(int percent) {
                    }

                    @Override
                    public boolean isCancelled() {
                        return gen != scanGen;
                    }
                });
                if (gen != scanGen) return;
                BookLibrary.sort(found, order);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != scanGen || !active) return;
                        applyScan(found);
                    }
                });
            }
        }, "reader-scan").start();
    }

    private void applyScan(List<LibraryEntry> found) {
        books = found;
        if (found.isEmpty()) {
            list.setBooks(found, 0);
            list.setMessage(host.t("No books yet"),
                    host.t("Put books into the Books folder or upload them from your computer"));
            return;
        }
        // Первая строка при порядке «по свежести» уже последняя читанная; при порядке по названию ищем её сами.
        int pick = 0;
        long newest = 0;
        for (int i = 0; i < found.size(); i++) {
            if (found.get(i).lastReadMs > newest) {
                newest = found.get(i).lastReadMs;
                pick = i;
            }
        }
        list.setBooks(found, pick);
    }

    @Override
    public void onActivate(int i) {
        if (i < 0 || i >= books.size()) return;
        openBook(books.get(i));
    }

    @Override
    public void onSelect(int i) {
        if (i < 0 || i >= books.size()) return;
        LibraryEntry e = books.get(i);
        coverGen++;
        ui.removeCallbacks(coverLoad);
        if (e == coverEntry && coverReady) {
            showCover(coverBitmap);
            return;
        }
        list.setCover(null);
        list.setPlaceholder(null);
        coverPending = e;
        ui.postDelayed(coverLoad, COVER_DEBOUNCE_MS);
    }

    private void showCover(Bitmap b) {
        if (b != null) {
            list.setCover(b);
        } else {
            list.setCover(null);
            list.setPlaceholder(robotBitmap());
        }
    }

    private Bitmap robotBitmap() {
        if (robot == null) {
            robot = BitmapFactory.decodeResource(rsvp.getResources(), R.drawable.book_cover_robot);
        }
        return robot;
    }

    private void loadCoverNow() {
        final LibraryEntry e = coverPending;
        if (e == null) return;
        final int gen = ++coverGen;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Bitmap b = decodeCover(library.coverFor(e));
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != coverGen || !active) return;
                        coverEntry = e;
                        coverBitmap = b;
                        coverReady = true;
                        showCover(b);
                    }
                });
            }
        }, "reader-cover").start();
    }

    /** Уменьшает до длинной стороны около 300 px: на слабом телефоне полноразмерная обложка книги не нужна. */
    private static Bitmap decodeCover(byte[] data) {
        if (data == null || data.length == 0) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int longSide = Math.max(o.outWidth, o.outHeight);
            if (longSide <= 0) return null;
            int sample = 1;
            while (longSide / (sample * 2) >= COVER_LONG_SIDE) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (OutOfMemoryError e) {
            // Обложка не обязательна: без неё показываем робота.
            return null;
        }
    }

    // ------------------------------------------------------------------ открытие книги

    private void openBook(final LibraryEntry e) {
        if (opening || !list.isListShown()) return;
        final String id = e.id();
        if (id == null) {
            host.toast(host.t("Could not open the book"));
            return;
        }
        opening = true;
        final int gen = ++openGen;
        buildPct = 0;
        list.setBusy(host.t("Preparing the book") + " 0%", 0);
        new Thread(new Runnable() {
            @Override
            public void run() {
                BookIndex idx = null;
                String err = null;
                try {
                    File cache = new File(cacheDir, id + ".zidx");
                    idx = BookIndex.loadCached(cache);
                    if (idx == null) {
                        Progress p = new Progress() {
                            @Override
                            public void onProgress(int percent) {
                                buildPct = percent;
                                if (!progressPending) {
                                    progressPending = true;
                                    ui.post(progressShow);
                                }
                            }

                            @Override
                            public boolean isCancelled() {
                                return gen != openGen;
                            }
                        };
                        boolean epub = e.path.toLowerCase(Locale.ROOT).endsWith(".epub");
                        idx = epub ? new EpubReader().read(e.file, p) : new Fb2Reader().read(e.file, p);
                        BookIndex.saveCached(cache, idx);
                    }
                } catch (InterruptedIOException cancelled) {
                    return;
                } catch (IOException ex) {
                    err = ex.getMessage();
                } catch (OutOfMemoryError oom) {
                    // Книга не помещается в память плеера: сообщаем как обычную ошибку открытия.
                    err = "";
                }
                final BookIndex done = idx;
                final String error = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != openGen || !active) return;
                        opening = false;
                        if (done == null) {
                            list.showList();
                            host.toast(ERR_NO_TEXT.equals(error) ? host.t("The book has no text")
                                    : host.t("Could not open the book"));
                            return;
                        }
                        startReading(e, id, done);
                    }
                });
            }
        }, "reader-open").start();
    }

    private void startReading(LibraryEntry e, String id, BookIndex idx) {
        long wall = System.currentTimeMillis();
        ReaderPositions.Position pos = positions.resolveOrAdopt(id, idx);
        ReaderPositions.Entry saved = positions.get(id);
        if (saved == null) saved = positions.ensure(id, idx.title, idx.author, e.path);

        int start = 0;
        String caption = null;
        if (pos != null && pos.word > 0 && saved.updatedMs > 0) {
            start = ResumePolicy.rollback(idx, pos.word, wall - saved.updatedMs);
            caption = host.t("Last time") + ": " + ReaderFormat.lastTime(wall, saved.updatedMs, TimeZone.getDefault(),
                    host.t("today"), host.t("yesterday"));
        }
        // Дочитанная книга при новом открытии начинается заново: с последнего слова читать нечего.
        if (idx.isEndReached(start)) {
            start = 0;
            caption = null;
        }
        int wpm = saved.wpm > 0 ? saved.wpm : prefs.getInt(PREF_WPM, DEFAULT_WPM);

        index = idx;
        bookId = id;
        current = e;
        touched = false;
        pausedAt = 0;
        movedWhilePaused = false;
        shownChapter = -1;
        engine = new ReaderEngine(idx, start, wpm);
        engine.setSplitOracle(rsvp);
        ReaderPositions.Position ret = positions.resolveReturnPoint(id, idx);
        if (ret != null) engine.setReturnPoint(ret.word);

        rsvp.setTypefaces(ThemeManager.getCustomFontTitle(), ThemeManager.getCustomFontBold(),
                ThemeManager.getCustomFont());
        rsvp.setLabels(host.t("words per minute"), host.t("left"), host.t("h"), host.t("min"));
        rsvp.setWordSize(wordSizePx());
        rsvp.setBook(idx);
        rsvp.setWord(engine.word(), engine.part());
        rsvp.setMode(RsvpView.MODE_PAUSED);
        showState();
        rsvp.setResumeCaption(caption);
        host.goTo(true);
    }

    private float wordSizePx() {
        int px = prefs.getInt(PREF_WORD_PX, ReaderFormat.WORD_SIZES_PX[ReaderFormat.DEFAULT_WORD_SIZE_INDEX]);
        return px;
    }

    /** Показ текущего слова и строки состояния. */
    private void showState() {
        rsvp.setWord(engine.word(), engine.part());
        int c = index.chapterFirstWord.length == 0 ? -1 : index.chapterOf(engine.word());
        if (c != shownChapter) {
            shownChapter = c;
            rsvp.setChapterTitle(c >= 0 && c < index.chapterTitles.length ? index.chapterTitles[c] : "");
        }
        rsvp.setProgress(engine.percent(), ReaderFormat.minutesLeft(engine.remainingMs()), engine.wpm());
    }

    /** Закрыть книгу и отпустить память: индекс большой, а держит его только открытая книга. */
    private void closeBook() {
        if (engine == null) return;
        ui.removeCallbacks(tick);
        engine.pause();
        trackNow();
        positions.flushNow();
        saveWpm();
        engine = null;
        index = null;
        bookId = null;
        rsvp.setBook(null);
    }

    private void saveWpm() {
        if (engine != null) prefs.edit().putInt(PREF_WPM, engine.wpm()).apply();
    }

    private void trackNow() {
        if (!touched || engine == null) return;
        positions.track(bookId, index, engine.word(), engine.wpm(), System.currentTimeMillis(),
                current == null ? null : current.path);
    }

    private void syncReturnPoint() {
        if (engine.hasReturnPoint()) positions.setReturnPoint(bookId, index, engine.returnPointWord());
        else positions.clearReturnPoint(bookId);
    }

    // ------------------------------------------------------------------ чтение

    private void onTick() {
        if (engine == null || !engine.isPlaying()) return;
        long now = SystemClock.uptimeMillis();
        if (engine.tick(now)) {
            showState();
            trackNow();
            positions.flushIfDue(now);
        }
        if (!engine.isPlaying()) {
            // Книга дочитана: оставляем последнее слово на паузе и сразу пишем позицию.
            pauseReading();
            return;
        }
        if (now - lastKeepAwake >= KEEP_AWAKE_MS) {
            lastKeepAwake = now;
            host.keepAwake();
        }
        ui.postAtTime(tick, engine.nextDueMs());
    }

    private void startPlayback() {
        if (engine == null || engine.isFinished()) return;
        long now = SystemClock.uptimeMillis();
        if (pausedAt > 0 && !movedWhilePaused) {
            // Долгая пауза: контекст забыт, откатываемся назад как при открытии книги.
            int back = ResumePolicy.rollback(index, engine.word(), now - pausedAt);
            if (back < engine.word()) engine.stepWords(back - engine.word());
        }
        touched = true;
        rsvp.setResumeCaption(null);
        rsvp.setMode(RsvpView.MODE_READING);
        engine.play(now);
        showState();
        lastKeepAwake = now;
        schedule();
    }

    private void pauseReading() {
        if (engine == null) return;
        ui.removeCallbacks(tick);
        engine.pause();
        rsvp.setMode(RsvpView.MODE_PAUSED);
        pausedAt = SystemClock.uptimeMillis();
        movedWhilePaused = false;
        showState();
        trackNow();
        positions.flushNow();
        saveWpm();
    }

    private void schedule() {
        ui.removeCallbacks(tick);
        if (engine != null && engine.isPlaying()) ui.postAtTime(tick, engine.nextDueMs());
    }

    /** После перемотки во время чтения: отсчёт слова начинается заново от сейчас. */
    private void rearm() {
        if (engine.isPlaying()) engine.play(SystemClock.uptimeMillis());
        schedule();
    }

    private void afterMove() {
        touched = true;
        if (!engine.isPlaying()) movedWhilePaused = true;
        rearm();
        showState();
        trackNow();
    }

    private void toggle() {
        if (engine.isPlaying()) pauseReading();
        else startPlayback();
    }

    // ------------------------------------------------------------------ клавиши

    public void onCenter() {
        if (reading && engine != null) toggle();
        else list.activate();
    }

    public void onPlayShort() {
        onCenter();
    }

    public void onWheel(int dir) {
        if (!reading || engine == null) {
            if (list.moveSelection(dir)) host.click();
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (engine.isPlaying()) {
            engine.stepWpm(dir);
            rsvp.setProgress(engine.percent(), ReaderFormat.minutesLeft(engine.remainingMs()), engine.wpm());
            rsvp.showWpmOverlay();
        } else {
            // Быстрый поворот колеса шагает предложениями: слово за словом по книге не дойти.
            if (ReaderFormat.isFastTurn(now - lastWheelAt)) engine.stepSentence(dir);
            else engine.stepWords(dir);
            afterMove();
        }
        lastWheelAt = now;
    }

    public void onBack() {
        if (opening) {
            // Подготовка книги отменяется, а не закрывает список.
            opening = false;
            openGen++;
            list.showList();
            return;
        }
        if (reading) {
            exitBook();
        } else {
            host.leave();
        }
    }

    private void exitBook() {
        closeBook();
        returningFromBook = true;
        host.goTo(false);
    }

    /** NEXT/PREV: касание = предложение (или страница списка), удержание = перемотка. */
    public void onNextPrev(boolean forward, int action, int repeat, boolean canceled) {
        int dir = forward ? 1 : -1;
        long now = SystemClock.uptimeMillis();
        if (action == KeyEvent.ACTION_DOWN) {
            if (repeat == 0) {
                seekDownAt = now;
                seekActive = false;
                lastSeekStep = 0;
                seekStartWord = reading && engine != null ? engine.word() : 0;
                return;
            }
            if (now - seekDownAt < HOLD_START_MS || !ReaderFormat.isSeekStepDue(now, lastSeekStep)) return;
            seekActive = true;
            lastSeekStep = now;
            if (reading && engine != null) {
                engine.seekRelativeWords(dir * ReaderFormat.seekWords(now - seekDownAt));
                afterMove();
            } else if (list.page(dir)) {
                host.click();
            }
            return;
        }
        if (action != KeyEvent.ACTION_UP) return;
        if (reading && engine != null) {
            if (seekActive) {
                // Мелкие шаги перемотки не оставляют точку возврата сами: ставим её, если ушли далеко.
                if (Math.abs(engine.word() - seekStartWord) > ReaderEngine.RETURN_POINT_JUMP) {
                    engine.setReturnPoint(seekStartWord);
                    syncReturnPoint();
                }
            } else if (!canceled) {
                engine.stepSentence(dir);
                afterMove();
            }
        } else if (!seekActive && !canceled && list.page(dir)) {
            host.click();
        }
        seekActive = false;
    }

    // ------------------------------------------------------------------ меню по удержанию PLAY

    public void onPlayHold() {
        if (reading && engine != null) showReaderMenu();
        else if (list.isListShown() && !opening) showLibraryMenu();
    }

    private void showReaderMenu() {
        pauseReading();
        final List<String> labels = new ArrayList<String>();
        final List<Integer> acts = new ArrayList<Integer>();
        labels.add(host.t("Contents"));
        acts.add(ACT_CONTENTS);
        labels.add(host.t("Bookmarks"));
        acts.add(ACT_MARKS);
        labels.add(host.t("Add bookmark"));
        acts.add(ACT_ADD_MARK);
        if (engine.hasReturnPoint()) {
            labels.add(String.format(Locale.US, host.t("Return to %d%%"), engine.returnPointPercent()));
            acts.add(ACT_RETURN);
        }
        labels.add(host.t("Speed"));
        acts.add(ACT_SPEED);
        labels.add(host.t("Word size"));
        acts.add(ACT_SIZE);
        labels.add(host.t("Exit"));
        acts.add(ACT_EXIT);
        host.showMenu(labels.toArray(new String[0]), 0, new MenuListener() {
            @Override
            public void onPick(int i) {
                if (engine == null) return;
                switch (acts.get(i)) {
                    case ACT_CONTENTS:
                        showContents();
                        break;
                    case ACT_MARKS:
                        showMarks();
                        break;
                    case ACT_ADD_MARK:
                        positions.addMark(bookId, index, engine.word(), System.currentTimeMillis());
                        positions.flushNow();
                        host.toast(host.t("Bookmark added"));
                        break;
                    case ACT_RETURN:
                        engine.returnToPoint();
                        syncReturnPoint();
                        afterMove();
                        break;
                    case ACT_SPEED:
                        showSpeed();
                        break;
                    case ACT_SIZE:
                        showWordSize();
                        break;
                    default:
                        exitBook();
                        break;
                }
            }
        });
    }

    private void showContents() {
        int n = index.chapterFirstWord.length;
        if (n == 0) {
            host.toast(host.t("No contents"));
            return;
        }
        String[] labels = new String[n];
        for (int i = 0; i < n; i++) {
            String title = i < index.chapterTitles.length ? index.chapterTitles[i] : null;
            labels[i] = title == null || title.length() == 0 ? String.valueOf(i + 1) : title;
        }
        host.showMenu(labels, index.chapterOf(engine.word()), new MenuListener() {
            @Override
            public void onPick(int i) {
                if (engine == null) return;
                engine.jumpToChapter(i);
                syncReturnPoint();
                afterMove();
            }
        });
    }

    private void showMarks() {
        ReaderPositions.Entry e = positions.get(bookId);
        if (e == null || e.marks.isEmpty()) {
            host.toast(host.t("No bookmarks yet"));
            return;
        }
        final List<ReaderPositions.Mark> marks = new ArrayList<ReaderPositions.Mark>(e.marks);
        String[] labels = new String[marks.size()];
        for (int i = 0; i < labels.length; i++) {
            ReaderPositions.Mark m = marks.get(i);
            StringBuilder sb = new StringBuilder(ReaderFormat.fragment(m.anchor, 40));
            if (m.chapterTitle != null && m.chapterTitle.length() > 0) sb.append(", ").append(m.chapterTitle);
            sb.append(", ").append(m.pct).append('%');
            labels[i] = sb.toString();
        }
        host.showMenu(labels, 0, new MenuListener() {
            @Override
            public void onPick(int i) {
                if (engine == null) return;
                engine.jumpToWord(positions.resolveMark(marks.get(i), index));
                syncReturnPoint();
                afterMove();
            }
        });
    }

    private void showSpeed() {
        int n = (ReaderEngine.MAX_WPM - ReaderEngine.MIN_WPM) / ReaderEngine.WPM_STEP + 1;
        String[] labels = new String[n];
        for (int i = 0; i < n; i++) labels[i] = String.valueOf(ReaderEngine.MIN_WPM + i * ReaderEngine.WPM_STEP);
        int focus = (engine.wpm() - ReaderEngine.MIN_WPM) / ReaderEngine.WPM_STEP;
        host.showMenu(labels, focus, new MenuListener() {
            @Override
            public void onPick(int i) {
                if (engine == null) return;
                engine.setWpm(ReaderEngine.MIN_WPM + i * ReaderEngine.WPM_STEP);
                saveWpm();
                showState();
                trackNow();
            }
        });
    }

    private void showWordSize() {
        String[] labels = {host.t("Small"), host.t("Medium"), host.t("Large")};
        int current = ReaderFormat.wordSizeIndex(prefs.getInt(PREF_WORD_PX,
                ReaderFormat.WORD_SIZES_PX[ReaderFormat.DEFAULT_WORD_SIZE_INDEX]));
        host.showMenu(labels, current, new MenuListener() {
            @Override
            public void onPick(int i) {
                int px = ReaderFormat.WORD_SIZES_PX[Math.max(0, Math.min(i, ReaderFormat.WORD_SIZES_PX.length - 1))];
                prefs.edit().putInt(PREF_WORD_PX, px).apply();
                rsvp.setWordSize(px);
            }
        });
    }

    private void showLibraryMenu() {
        int sel = list.getSelectedIndex();
        if (sel < 0 || sel >= books.size()) return;
        final LibraryEntry e = books.get(sel);
        final boolean isRead = e.state == LibraryEntry.READ;
        String[] labels = {host.t("Start over"), host.t(isRead ? "Mark as unread" : "Mark as read"),
                host.t("Order")};
        host.showMenu(labels, 0, new MenuListener() {
            @Override
            public void onPick(int i) {
                if (i == 0) {
                    BookLibrary.restart(positions, e);
                    afterLibraryChange(e);
                } else if (i == 1) {
                    if (isRead) BookLibrary.markUnread(positions, e);
                    else BookLibrary.markRead(positions, e);
                    afterLibraryChange(e);
                } else {
                    showOrderMenu(e);
                }
            }
        });
    }

    private void showOrderMenu(final LibraryEntry keep) {
        String[] labels = {host.t("By last read"), host.t("By title")};
        host.showMenu(labels, order == BookLibrary.ORDER_TITLE ? 1 : 0, new MenuListener() {
            @Override
            public void onPick(int i) {
                order = i == 1 ? BookLibrary.ORDER_TITLE : BookLibrary.ORDER_RECENT;
                prefs.edit().putInt(PREF_ORDER, order).apply();
                afterLibraryChange(keep);
            }
        });
    }

    /** Состояние книги или порядок изменились: список пересортирован, выбор остался на той же книге. */
    private void afterLibraryChange(LibraryEntry keep) {
        positions.flushNow();
        BookLibrary.sort(books, order);
        list.reorder(Math.max(0, books.indexOf(keep)));
        list.refresh();
    }
}
