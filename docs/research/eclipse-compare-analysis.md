# Eclipse 2026-03: compare / JDT / EGit — što se može iskoristiti

Izvor: dekompilirani bundleovi iz `~/dev/eclipse/plugins` (compare 3.12.0, compare.core 3.8.800, jdt.ui 3.37, egit.ui 7.6, ui.editors 3.21, genericeditor 1.4, tm4e.ui 0.17.2) + runtime snimke (`docs/eclipse-screens/`, 2026-10-13).
Oznake: [O] viđeno u kodu/deskriptoru · [I] zaključeno · [U] nepoznato · [S] snimka.

## A. org.eclipse.compare `TextMergeViewer` (TMV)

- Layout je fiksan: ancestor pane **gore**, ispod `left | center | right`. Srednji editabilni panel nije moguć. [O `ContentMergeViewer`, `handleResize*` final]
- Center ribbon `createCenterControl()` je **protected final**, širina `CENTER_WIDTH=34` (final). Crta se jedna kosinusna krivulja i 3px krajnje crtice (`USE_SINGLE_LINE`=true); na hover se pojavljuju gumbi `>` / `<` (kopiranje jednog diffa). [O]
- Overview: `fBirdsEyeCanvas` širine 12px. Jedan zajednički scrollbar (`fScrollCanvas`). [O]
- Pozadina diffova:
  - `LineBackgroundListener` puni pozadinu bojom `getFillColor`, a preskače caret line;
  - `paint(...)` crta rubove;
  - `ChangeHighlighter implements ITextPresentationListener` spaja token-level `StyleRange` pozadinu sa syntax bojama. **Taj obrazac preuzimamo.** [O]
- Diff model: `DocumentMerger` (x-internal) → `RangeDifferencer.findRanges(anc, left, right)` (javni API compare.core). Vrste: CONFLICT=1, RIGHT=2, LEFT=3, ANCESTOR=4. [O]
- Mogu se overrideati: `createSourceViewer`, `configureTextViewer`, `getDocumentPartitioner/-Partitioning`, `createTokenComparator`, `createToolItems`, `copy(boolean)`, `doSave`.
- Privatno ili final: svo crtanje (`paintCenter`, `paintSides`, `paintBirdsEyeView`, `getFillColor`, `ColorPalette`), `DocumentMerger`, `MergeSourceViewer`. [O]
- Shared document: `ContributorInfo` → `ISharedDocumentAdapter` → `DocumentProviderRegistry`. Workspace `.java` datoteka dijeli **isti dokument/working copy kao Java editor**. [O]

### Preference i boje [O]
- Preference: `org.eclipse.compare.SynchronizeScrolling`=true, `.ShowPseudoConflicts`=false, `.InitiallyShowAncestorPane`=false, `.IgnoreWhitespace`=false, `.UseSingleLine`=true, `.HighlightTokenChanges`=true, `.Swapped`=false.
- Theme `colorDefinition` (kategorija `org.eclipse.compare.contentmergeviewer.TextMergeViewer`); dark vrijednosti dolaze iz `org.eclipse.ui.themes/css/dark/e4-dark_preferencestyle.css`:

| id | light | dark |
|---|---|---|
| INCOMING_COLOR | 0,0,255 | 31,179,235 |
| OUTGOING_COLOR | 0,0,0 | 238,238,238 |
| CONFLICTING_COLOR | 255,0,0 | 240,15,66 |
| RESOLVED_COLOR | 0,255,0 | 108,210,17 |
| ADDITION_COLOR | 0,128,0 | – |
| DELETION_COLOR | 255,0,0 | – |
| EDITION_COLOR | 0,0,0 | 238,238,238 |

- Nijanse se izvode iz jedne boje: `stroke = lerp(sel, bg, 0.6)`, `fill = lerp(sel, bg, 0.9)`, `textFill = lerp(sel, bg, 0.8)`.
- Izmjereno na tvom Eclipseu [S]: pozadina #FAFAFA, konflikt fill #FAE1E1, outgoing #E4E4E4, incoming #E1E1FA.

## B. JDT `JavaMergeViewer` (x-friends)
- Postavke:
  - particioniranje: `JavaCompareUtilities.createJavaPartitioner()`, `"___java_partitioning"`;
  - token diff: `JavaTokenComparator`;
  - prefs: ulančani store (projektni jdt.core → JavaPlugin → JavaCore → EditorsUI). [O]
- `createSourceViewer`: unutarnja klasa `CompilationUnitEditorAdapter extends CompilationUnitEditor` dobiva viewer **refleksijom** (`fSourceViewer`). Ako je ulaz editor-backed (`ISharedDocumentAdapter`), poziva se `editor.init(site, input)`. Tada se instaliraju `JavaReconciler`, squiggles, hover, Ctrl+1 i content assist. [O]
- Read-only strane dobivaju samo leksičko bojanje (`JavaSourceViewerConfiguration` bez editora). [O]
- **Nema annotation rulera ni overview rulera** u compareu, i **nema semantic highlightinga**: `installSemanticHighlighting()` se zove samo iz `JavaEditor.createPartControl`. [O/I]
- Snimka potvrđuje: compare paneli nemaju markere grešaka, iako Problems view prikazuje 8 grešaka. [S]
- Working copy na običnom dokumentu (javni API):
  - `ICompilationUnit.getWorkingCopy(WorkingCopyOwner, monitor)` + `IProblemRequestor` + `reconcile(AST.getJLSLatest(), true, owner, monitor)`;
  - za merge panel je još jednostavnije: `JavaUI.getDocumentProvider().connect(new FileEditorInput(file))` → `getDocument` i `getAnnotationModel` (`CompilationUnitAnnotationModel`). [I]

## C. Ostali jezici [O]
- Extension pointovi `org.eclipse.compare.contentMergeViewers` i `contentViewers` (s `linkedEditor`, `contentTypeBinding`).
- Generic editor: `GenericEditorMergeViewer extends TextMergeViewer` + `ExtensionBasedTextViewerConfiguration` (internal); daje samo prezentaciju.
- tm4e: `org.eclipse.tm4e.ui.text.TMPresentationReconciler` (javni paket); u compare stiže samo preko generic editora.

## D. EGit 7.6 Merge Tool [O, S]
- Komanda `org.eclipse.egit.ui.team.MergeTool`, handler `MergeToolActionHandler`, enablement `GitSelection.conflictsInSingleRepository`.
- Pref `merge_mode`: 0 = pitaj (dijalog [S]), 1 = WORKTREE (s markerima), 2 = STAGE_2 (HEAD), 3 = MERGED_OURS (default u dijalogu; skriveni resurs `EgitFileSystem … "WORKTREE:o"`).
- Paneli:
  - left (editabilan) = prema modu;
  - right = stage 3;
  - ancestor = merge-base commit (fallback stage 1).
- **Save ne radi `git add`.** `merge_tool_auto_add` vrijedi samo za vanjski alat. [O]
- Za vlastiti editor nema extension pointa. Opcije:
  - nadjačati handler komande s užim `activeWhen`;
  - vlastita komanda s istim enablementom;
  - `contentMergeViewers` (to utječe na sve usporedbe tog tipa).
- [S] U MERGED_OURS modu regija `divide` (git konflikt) prikazuje se kao siva "outgoing", ne kao konflikt: konflikt je već unaprijed riješen u korist "ours".

## E. Eclipse boje [O]

### JDT (light / dark)
| ključ | light | dark |
|---|---|---|
| java_default | 0,0,0 | 217,232,247 |
| java_keyword (bold) | 127,0,85 | 204,108,29 |
| java_string | 42,0,255 | 23,198,163 |
| java_comment | 63,127,95 | 128,128,128 |
| javadoc default/keyword/tag/link | 63,95,191 / 127,159,191 / 127,127,159 / 63,63,191 | 128,128,128 / 154,140,124 / 30,120,155 / 169,156,140 |
| semantic field / staticField | 0,0,192 | 102,225,248 / 141,218,248 |
| semantic localVariable / parameter | 106,62,62 | 243,236,121 / 121,171,255 |
| semantic class / interface / enum | 0,80,50 / 50,63,112 / 100,70,50 | 18,144,195 / 128,242,246 / 204,129,186 |
| semantic method / annotation / number | 0,0,0 / 100,100,100 / 42,0,255 | 167,236,33 / 160,160,160 / 104,151,187 |

### Editor
| ključ | light | dark |
|---|---|---|
| background | sistemska (255,255,255) | 30,31,34 |
| foreground | 0,0,0 | 204,204,204 |
| currentLineColor | 232,242,254 | 55,55,55 |
| lineNumberColor | 115,115,115 | 119,145,154 |
| selection bg | sistemska | 33,66,131 |

### Anotacije (`MarkerAnnotationPreferences`)
| anotacija | light | dark | stil |
|---|---|---|---|
| error | 255,0,128 | isto | underline |
| warning | 244,200,45 | isto | underline |
| info | 130,160,190 | 86,194,170 | squiggles |
| task | 0,128,255 | isto | |

## F. Preporuka
1. **Subclass TMV / JavaMergeViewer:** odbaciti. Layout, crtanje i model su privatni ili final; SmartGit UX nije ostvariv.
2. **Vlastiti `ContentMergeViewer`:** srednje-visok trud, jer se treba boriti s nametnutim layoutom.
3. **Vlastiti editor s 3 SourceViewera (PREPORUKA):**
   - Ulaz: JGit stage 2/3 (+1) i working-tree `IFile`.
   - Merge panel: shared document preko `JavaUI.getDocumentProvider()` + JDT adapter trik (kao `JavaMergeViewer`) za reconcile, quick fix, content assist i semantic highlighting.
   - Bočni paneli: `JavaSourceViewerConfiguration` (leksičko bojanje).
   - Diff: JGit `HistogramDiff` ili `RangeDifferencer`.
   - Crtanje: `LineBackgroundListener` + `ITextPresentationListener` + `PaintListener` + Bézier Canvas + Slider.
   - Boje iz `JFaceResources.getColorRegistry()` / `EditorsUI.getPreferenceStore()`.
   - Mark resolved: `git.add()` / `AddToIndexOperation`.

Discouraged/internal API koji bi se dotaknuo:
- `org.eclipse.jdt.internal.ui.javaeditor` (`CompilationUnitEditor`, `SemanticHighlightingManager`);
- `org.eclipse.egit.ui.internal.*`;
- `org.eclipse.compare.internal.*` (izbjegavati).

Equinox to ne blokira u runtimeu; PDE samo upozorava.

[U] Rade li `JavaCorrectionAssistant` / content assist s minimalnim `ITextEditor` koji nije `CompilationUnitEditor`.
