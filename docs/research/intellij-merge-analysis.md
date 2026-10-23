# IntelliJ IDEA 3-way merge: kako radi i kako automatski razrješava

**Izvori:**
- intellij-community (master, 2026-10-13, Apache 2.0), sparse clone;
- instalirani IU 2026.2.3 (262.10968.63), usporedba jar ↔ source;
- runtime snimke u `docs/intellij-screens/`.

**Kratice:**
- `DI` = `platform/diff-impl/src/com/intellij/diff`
- `UD` = `platform/util/diff/src/com/intellij/diff`
- `VM` = `platform/vcs-impl/src/com/intellij/openapi/vcs/merge`
- `GM` = `plugins/git4idea/backend/src/merge`

**Oznake:** O = viđeno · I = zaključeno · U = nepoznato · S = snimka.

## Što se isporučuje (jar vs repo)
- Sve klase iz popisa isporučuju se u `intellij.platform.diff.impl.jar` / `intellij.platform.util.diff.jar` i imaju izvorni kod (O).
- Gdje su deklarirane:
  - `TextAutoResolver` → `DI/merge/MergeConflictModel.kt:510`;
  - `ResolveConflictsInImportsToggleAction` i `MergeLineFragmentsWithImportMetadata` → `MergeImportUtil.kt`;
  - `IterativeResolveSupport` → `IterativeResolveData.kt`.
- `DiffConfig.USE_GREEDY_MERGE_MAGIC_RESOLVE = false` (javap) (O).
- EP `com.intellij.diff.merge.conflict.semantic.resolver`: isporučuje se **samo** `PropertiesMergeConflictResolver` (O).
- Import blokovi: `JavaImportBlockRangeProvider`, `KotlinImportBlockRangeProvider` (O).
- Registry (default):
  - `semantic.merge.conflict.resolution=true`
  - `semantic.merge.recompute.after.change=true`
  - `vcs.merge.conflict.iterative.resolution=true`
- AI ("Resolve with AI") je closed-source plugin koji nije instaliran. U open sourceu postoje samo hookovi:
  - `MergeConflictModel.replaceChangeWithAi`;
  - boja `0x834DF0` za AI-riješene promjene i Revert ikona;
  - EP `com.intellij.openapi.vcs.merge.resolveActionProvider` (O).
  - Ponašanje samog AI-ja je U.

## 1. Paneli i početni rezultat
- **Ulaz:** stageovi se čitaju s `git show :1:/:2:/:3:` → LEFT = ours (2), BASE = 1, RIGHT = theirs (3) (`GM/GitMergeUtil.java`, `VM/MultipleFileMergeDialog.kt:669`) (O).
- **Rebase:** ours i theirs se zamjenjuju (`isReversed`), pa je lijevo uvijek "tvoje"; registry `git.do.not.swap.merge.conflict.sides` to gasi (O).
- **BASE s markerima** (virtualni base iz recursive/ort) zamjenjuje se pravim merge-base commitom. Ako stage 1 nedostaje (add/add), koristi se radna datoteka (O).
- **Srednji panel je pravi `Document` radne datoteke**, ali se kod svakog `rediff` njegov tekst **prepisuje BASE tekstom** (`MergeConflictModel.kt:102-121`, non-undoable).
  - Rezultat je uvijek "base + primijenjeno", nikad git-ovi markeri (O).
  - Original s markerima čuva se za Cancel (O).
- [S] Promjene se zapisuju u datoteku na disku i ostaju tamo i nakon "Discard Changes" u Conflicts dijalogu (naš test: `precision = 2` + spojeni dijelovi). To ponašanje izbjeći.

## 2. Diff/merge model
- `MergeLineFragment` drži L/B/R line range.
- `MergeConflictType`:
  - tip: INSERTED / DELETED / MODIFIED / CONFLICT;
  - `isChange(side)`;
  - `resolutionStrategy`: DEFAULT (nije konflikt), TEXT (rješivo na razini riječi), SEMANTIC (jezični resolver), null (nerješivo).
- **3-way line merge** (`ByLineRt.merge`):
  1. diff BASE↔LEFT i BASE↔RIGHT (smart, whitespace-insensitive, pa korekcija stvarnom policyjem);
  2. `ComparisonMergeUtil.FairMergeBuilder`: presjek "unchanged" raspona u base koordinatama daje jednake triplete, a sve između postaje `MergeRange`;
  3. `IgnoringChangeBuilder` izdvaja promjene samo u whitespaceu kao zasebne male raspone.
- **Klasifikacija** (`MergeRangeUtil.getMergeType`):
  - base prazan, jedna strana prazna → INSERTED;
  - base prazan, obje strane neprazne → INSERTED(both) ako su jednake, inače CONFLICT, null (insert/insert nikad nije rješiv);
  - obje strane prazne → DELETED(both);
  - obje jednake base (po ignore policyju) → MODIFIED (samo whitespace);
  - jedna strana nepromijenjena → DELETED ili MODIFIED s druge strane;
  - obje promijenjene identično → MODIFIED(both);
  - inače CONFLICT; strategija je TEXT ako su obje strane neprazne i `tryResolveConflict(l,b,r) != null`.
- **Policyji:**
  - ignore: DEFAULT / TRIM_WHITESPACES / IGNORE_WHITESPACES;
  - highlight: BY_LINE / BY_WORD (default BY_WORD).
- **Word highlight 3-way** (`DiffUtil.compareThreesideInner`): `ByWord(base,left)` + `ByWord(base,right)` po nerazriješenoj promjeni, u pozadini, s debounceom; za već riješene strane se ne računa.

## 3. Auto-rezolucija (jezgra)

### (a) Nekonfliktne promjene: "Apply non-conflicting changes: Left | All | Right"
- `applyNonConflictedChanges(side)` → `resolveChangeAutomatically` za svaku nekonfliktnu promjenu, u jednoj undo naredbi.
- Promjena se primjenjuje samo ako:
  - nije riješena;
  - mijenja traženu stranu (`isChange(side)`);
  - **raspon u rezultatu je još jednak BASE-u** (ručne izmjene se nikad ne prepisuju).
- "All" uzima stranu koja se promijenila (lijeva ima prednost).

### (b) "Resolve Simple Conflicts" (čarobni štapić)
- `applyResolvableConflictedChanges` obrađuje sve promjene, pa primjenjuje i nekonfliktne.
- Uz to postoje gutter ikona štapića po konfliktu i popup "Resolve Automatically".
- Uvjet: strategija nije null, nijedna strana još nije primijenjena, i za TEXT je raspon nepromijenjen. Modify/delete datoteke se nikad ne rješava automatski.
- `MergeResolveUtil.tryResolve` = `SimpleHelper` s DEFAULT policyjem, pa s IGNORE_WHITESPACES:

```
SimpleHelper(l,b,r,policy):
  frags = ByWordRt.compare(l,b,r,policy)          // 3-way konflikti na razini RIJEČI
  last=(0,0,0); out=""
  for f in frags:
     appendBase(gap last→f.start):  l==b==r → b; inače uzmi stranu koja se promijenila
     t = wordMergeType(f)
     if t == CONFLICT: return null                 // obje strane različito promijenile iste riječi
     out += t.isChange(L) ? l[f] : r[f]
     last = f.end
  appendBase(last→kraj); return out
```

- Primjeri (base / left / right → rezultat):
  - `"x x x" / "x Y x x" / "x x Z x"` → `"x Y x Z x"`
  - `"x x x" / "x Y x" / "x Z x"` → null
- **Greedy varijanta** (`tryGreedyResolve`) postoji, ali je **isključena**. Ona spaja preklapajuća brisanja, a za dva različita umetka uzima kraći ako su jednaki, inače vraća null. Npr. base `"x X x"`, left `"x x"`, right `"x X Y x"` → `"x Y x"`.
- Obrazloženje: A-X-B i B-X-A moraju dati isti rezultat, pa se dva različita umetka nikad ne mogu poredati.
- [S] U našem testu štapić je spojio `divide`: rezultat sadrži i `if (b==0) throw` i `b == 0 ? NaN`. Tekstualno je ispravno, ali semantički sumnjivo. **Pouka: auto-riješeno označiti za pregled i omogućiti revert po bloku.**

### (c) `TextAutoResolver`
Omotač oko (b), uzima trenutni rezultat kao base.

### (d) Jezično-specifično
- **API:** `isApplicable(Language)`, `tryResolveMergeConflicts(ctx): List<CharSequence?>` (null = ne može). Context daje fragmente i PSI svake strane.
- **Jedina implementacija: `.properties`.**
  - Unija ključeva (izlaz sortiran po ključu).
  - Isti ključ mora imati jednaku vrijednost.
  - Četiri filtra odbijaju konflikt: ključ dodan i drugdje, nekonzistentno brisanje, presjek komentara, nekonzistentni komentari.
  - Izlaz se formatira code-styleom.
- **Importi (Java/Kotlin)**, setting `MERGE_AUTO_RESOLVE_IMPORT_CONFLICTS`, default **false**:
  - Preduvjet: PSI postoji za sve tri strane i sve hard reference u L i R se resolveaju.
  - Merge se dijeli na `[prije | importi | poslije]` tako da fragmenti ne prelaze granicu import bloka.
  - Pri otvaranju se import promjene označe kao riješene bez diranja teksta, pa ostaju BASE importi.
  - Kad se primijeni promjena u tijelu, `ChangeReferenceProcessor` ponavlja "paste" logiku (`ReferenceCopyPasteProcessor`) i dodaje potrebne importe. Neiskorišteni importi se ne brišu (I).
- **`IterativeResolveSupport`:** unaprijed izračunat model omogućuje auto-resolve datoteka bez otvaranja viewera (multi-file dijalog).

### (e) Postavke i otvaranje
- "Automatically apply non-conflicting changes": default **false**.
- "Automatically resolve conflicts in import statements": default false.
- Pri otvaranju: rezultat = BASE → highlighteri → (importi) → (auto-apply ako je uključen).
- Simple konflikti se pri otvaranju **nikad** ne rješavaju automatski. Za to treba štapić ili "Resolve All Simple Conflicts" u multi-file dijalogu [S `conflicts-dialog.png`], koji javlja "N conflicts resolved. M still require attention".

## 4. UX [O + S `merge-initial.png`, `merge-after-magic-wand.png`]
- **Paneli:** lijevo yours (RO), sredina "Result" (editabilan), desno theirs (RO).
  - Naslovi: "Changes from main" / "Result src/…/Calc.java" / "Changes from feature", plus "Show Details".
- **Divideri:** nerazriješeno = popunjen poligon; riješeno = **točkasti obrub bez ispune**. Promjena se crta samo na stranama na koje se odnosi.
- **Boje:** `DiffColors.DIFF_INSERTED/DELETED/MODIFIED/CONFLICT`; riješena strana se crta samo obrubom.
- **Gutter:**
  - u rezultatu: štapić "Resolve" na rješivim konfliktima;
  - na bočnim panelima: `»` / `«` Accept i `×` Ignore;
  - nakon prve strane konflikta accept postaje "append";
  - Ctrl+klik razrješava cijeli konflikt.
- **Accept jedne strane konflikta:** prvi accept zamjenjuje raspon, drugi (s druge strane) **dodaje ispod**.
- **Toolbar:** Compare Contents (L↔M, R↔M, L↔R, diff s base) · Collapse unchanged · "Apply non-conflicting changes: Left | All | Right" · štapić · Revert resolution · zupčanik (auto-scroll, importi, policyji).
- **Status:** "{n} changes, {m} conflicts", zelena kvačica kad je sve 0, balon "All changes have been processed → Apply".
- **Prečaci:** Accept Left Ctrl+Alt+R, Accept Right Ctrl+Alt+A, F7 / Shift+F7.
- **Gumbi:** Accept Left, Accept Right, Apply Changes, Cancel ("Save and Close").
  - Apply s neobrađenim promjenama pita za potvrdu, a zatim sprema **s base tekstom u nerazriješenim rasponima**.
- **Praćenje tijekom tipkanja:**
  - rasponi su `IntList` s `updateRangeOnModification`;
  - tipkanje NE označava promjenu riješenom, osim DELETED kad raspon postane prazan;
  - svaka izmjena raspona gasi njegov auto-resolve;
  - undo/redo vraća stanja promjena.
- **Multi-file dijalog:**
  - iterativni (default): Unresolved/Resolved grupe s brojem resolved/total po datoteci, "Resolve All Simple Conflicts", Resolve Manually, Accept '{branch}', Revert, Accept and Finish;
  - klasični: Accept Yours / Accept Theirs / Merge…

## 5. Git
- Popis konfliktnih datoteka: `git ls-files --unmerged`.
- Resolved: `git add` (ili `git rm` ako je prihvaćena strana obrisala datoteku).
- Accept Yours/Theirs: `git checkout --ours|--theirs` (s rebase swapom).

## 6. Prioriteti za Eclipse port (Apache 2.0, zadržati JetBrains copyright i NOTICE)
- **P1** Model: L/B/R iz stageova, BASE fallbackovi, swap kod rebasea.
- **P2** 3-way line merge (`FairMergeBuilder`) + klasifikacijska tablica (~190 linija).
- **P3** Apply non-conflicting (Left/All/Right) uz zaštitu "raspon == base", plus opcija "on open".
- **P4** `SimpleHelper` word-level resolver + "Resolve All Simple Conflicts" (po datoteci i za sve datoteke); port `MergeResolveUtilTest`.
- **P5** State machine promjena + praćenje raspona (Eclipse `Position` + compound undo).
- **P6** Rendering: ispuna nerazriješenog, točkasti obrub riješenog; gutter `»` `«` `×` i štapić; status.
- **P7** Word-level 3-way highlight.
- **P8** Java importi preko JDT-a: set-based 3-way merge importa (unija minus brisanja) + `ImportRewrite` / Organize Imports. Bolje od IntelliJ-ovog paste hacka (I).
- **P9** Plugabilni semantic resolver EP (+ `.properties` resolver; kasnije JDT member-level: obje strane dodale različite metode).
- **P10** Iterativni multi-file dijalog.
- **P11** Hookovi: Apply-with-unresolved, Cancel vraća original, AI resolver s posebnom bojom i revertom.

**Praznine koje možemo popuniti (I):**
- greedy kao opt-in drugi prolaz označen "needs review";
- insert/insert gdje je jedan umetak prefiks/sufiks drugog;
- modify/delete.
