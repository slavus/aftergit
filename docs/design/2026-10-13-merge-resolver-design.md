# AfterGit Merge Resolver za Eclipse — specifikacija dizajna

- **Datum:** 2026-10-13
- **Status:** dizajn odobren po sekcijama, čeka pregled pisane specifikacije
- **Paket / bundle prefiks:** `net.aftergit`

**Istraživanje na kojem se dizajn temelji:**
- the SmartGit reference notes (kept privately, not in this repository) — SmartGit Conflict Solver: izgled, boje, model;
- `docs/research/eclipse-compare-analysis.md` — Eclipse Compare, JDT, EGit;
- `docs/research/intellij-merge-analysis.md` — IntelliJ merge i auto-rezolucija;
- snimke: the SmartGit reference screenshots (kept privately, not in this repository), `docs/eclipse-screens/`, `docs/intellij-screens/`;
- https://www.eseth.org/2020/mergetools.html — teza: kreni od git-ovog rezultata i ne ponavljaj ono što je git već riješio.

---

## 1. Cilj i kriteriji uspjeha

**Cilj:** Eclipse plugin s **kvalitetnijim rješavanjem merge konflikata** od postojećih alata. Spaja tri izvora:
- **SmartGit:** UX i izgled 3-pane Conflict Solvera (ours | rezultat | theirs, Bézier spojnice s take-gumbima, trake pregleda, zajednički scrollbar);
- **IntelliJ IDEA:** automatska rezolucija svega što se može riješiti (nekonfliktne promjene, „simple“ konflikti na razini riječi, importi), model stanja blokova;
- **Eclipse:** JDT bojanje sintakse (uključujući semantičko), greške kompajliranja uživo, quick fix i content assist u rezultatu, Eclipse teme i boje, Compare editor kao domaćin.

**Kriteriji uspjeha (v1):**
1. Za konfliktnu Java datoteku otvara se editor sa SmartGit rasporedom. Srednji panel pokazuje git-ov rezultat, a konflikti su blokovi umjesto sirovih markera.
2. Sve što se može riješiti automatski riješeno je pri otvaranju, jasno označeno i može se poništiti po bloku.
3. U srednjem panelu rade JDT bojanje, greške uživo, Ctrl+1 i Ctrl+Space. Auto-riješen blok koji ne kompajlira posebno je označen.
4. Konflikti u Java importima nestaju automatski i ispravno.
5. „Resolve All Simple Conflicts“ radi nad više datoteka bez otvaranja editora.
6. „Save & Mark Resolved“ sprema datoteku i radi `git add`. Cancel vraća datoteku točno u stanje prije otvaranja.

**Izvan opsega v1** (vidi §11 Faze): MCP server i AI resolver, JDT member-level semantika, `.properties` resolver, Structure Compare panel, semantičko bojanje bočnih panela, LSP za ostale jezike, samostalni CLI.

---

## 2. Odluke donesene u dizajnu

| # | Odluka | Odabrano | Razlog |
|---|---|---|---|
| D1 | Jezici | Java puna integracija (JDT); ostali jezici osnovno bojanje (tm4e); svi jezici preko LSP-a u fazi 3 | najbolji omjer vrijednosti i truda |
| D2 | Početno stanje rezultata | **Hibrid:** git-ov rezultat bez markera, konflikti kao blokovi, auto-rezolucija odmah, revert po bloku | eseth.org teza + IntelliJ auto-resolve + SmartGit pivot na rezultat |
| D3 | Boje | SmartGit paleta registrirana kao Eclipse theme `colorDefinition` (light + dark) + boje za auto- i AI-riješeno | SmartGit izgled, ali podesivo i u skladu s Eclipse temama |
| D4 | Ulazna točka | **Zasebna komanda** uz EGit; EGitov Merge Tool ostaje netaknut (kasnije preokrenuto: AfterGit preuzima EGitovu komandu Merge Tool za jednu tekstnu konfliktnu datoteku, vidi ui-polish dizajn §4) | nema konflikta s EGitom |
| D5 | Arhitektura | **Pristup A:** vlastiti 3-pane widget + zaseban merge engine; domaćin je Compare editor (`CompareEditorInput.findContentViewer`) | puna kontrola izgleda; lifecycle/save od Compare frameworka |
| D6 | Opseg v1 | jezgra + Java importi + quick fix / content assist + Resolve All (više datoteka) + Mark as Resolved | odluka korisnika |
| D7 | AI | v1: stanje `AI_RESOLVED`, revert, prikaz, interni `ConflictResolverService`; faza 2: MCP server i „Resolve with AI“ | arhitektura spremna od početka |

Odbačeni pristupi:
- nasljeđivanje `TextMergeViewer` / `JavaMergeViewer`: raspored i crtanje su privatni ili final, nema editabilnog srednjeg panela;
- tri puna `CompilationUnitEditor`a: teško se kontrolira crtanje i skrolanje;
- vlastiti `ContentMergeViewer`: nameće raspored s ancestor panelom i dva stupca.

---

## 3. Arhitektura

### 3.1 Bundleovi (Maven Tycho, Java 21, target Eclipse 2026-03)

| Bundle | Odgovornost | Ovisi o |
|---|---|---|
| `net.aftergit.merge.core` | engine: line/word diff adapteri, 3-way merge, klasifikacija, resolveri (Simple, Greedy, pluggable), parser markera (uključujući diff3), model blokova i stanja, hibridna inicijalizacija, `ConflictResolverService` | JGit (`org.eclipse.jgit.diff`); **bez Eclipse UI/OSGi servisa** |
| `net.aftergit.merge.git` | čitanje stageova i W, base fallbackovi, naslovi panela, Save / Mark Resolved, Resolve All, modify/delete dijalog-logika | core, JGit, EGit core (javni API: `RepositoryMapping`) |
| `net.aftergit.merge.jdt` | `JdtResultEditorAdapter` (jedino mjesto s internim JDT API-jem), degradirani način, import resolver (`ImportRewrite`), validacija po blokovima, reconcile ours/theirs | core, JDT core/UI |
| `net.aftergit.merge.ui` | `MergeEditorInput` (`CompareEditorInput`), 3-pane widget, paneli, spojnice, trake pregleda, scroll sinkronizacija, komande / handleri / prečaci, theme boje i dark CSS, tm4e bojanje ne-Java datoteka, dijalozi | sve gore, `org.eclipse.compare`, `org.eclipse.ui.editors`, tm4e (opcionalno) |
| `net.aftergit.merge.feature` + p2 update site | instalacija | |
| `net.aftergit.merge.core.tests` | JUnit 5: engine, property testovi, korpus | core |
| `net.aftergit.merge.ui.tests` | SWTBot + PDE: tokovi u editoru, git, JDT | sve |

### 3.2 Tok podataka

```
EGit/JGit ──(stage 1/2/3, W, labele)──► merge.git ──► MergeInput
MergeInput ──► core.HybridInitializer ──► MergeModel (blokovi + stanja + početni tekst rezultata)
MergeModel ◄──► core.ResolverPipeline (prolazi 0..3, + pluggable resolveri, + AI kasnije)
MergeModel ◄──► ui.MergeViewer (crtanje, Take/Ignore/Revert, tipkanje → model.onDocumentChange)
ui.MergeViewer ── srednji panel = JDT editor (merge.jdt) nad shared dokumentom ──► greške → MergeModel.blockDiagnostics
Save ──► merge.git (zapiši W' / markere, git add)
```

### 3.3 Granice i sučelja (ključni tipovi u `core`)

```java
record MergeInput(Text base, Text ours, Text theirs, Text workingTree,
                  Labels labels, Charset charset, LineEnding eol, boolean reversed) {}

enum ChangeType { INSERTED, DELETED, MODIFIED, CONFLICT }
enum Strategy   { DEFAULT, TEXT, GREEDY, IMPORTS, SEMANTIC, NONE }

enum BlockState { UNRESOLVED, RESOLVED_BY_GIT, AUTO_RESOLVED, AI_RESOLVED,
                  TAKEN_LEFT, TAKEN_RIGHT, TAKEN_BOTH, IGNORED, EDITED }

final class MergeBlock {
  LineRange left, base, right;        // u izvornim tekstovima
  Position  result;                   // u dokumentu rezultata (prati tipkanje)
  ChangeType type; Strategy strategy; // klasifikacija
  BlockState state; String resolvedBy;// npr. "simple", "imports", "greedy", "ai:<model>"
  String baseText;                    // za Revert
  String rationale;                   // AI obrazloženje (faza 2)
  boolean needsReview;                // auto/AI/greedy dok korisnik ne potvrdi
  List<Diagnostic> diagnostics;       // JDT greške mapirane na blok
}

interface ConflictResolver {          // pluggable (extension point net.aftergit.merge.resolvers)
  String id();
  boolean isApplicable(MergeContext ctx);
  Optional<Resolution> tryResolve(MergeContext ctx, MergeBlock block);   // null = ne može
}

interface ConflictResolverService {   // interni API, temelj za MCP u fazi 2
  List<ConflictFile> listConflicts(Repository repo);
  ConflictView getConflict(Path file, String blockId);
  ResolveReport autoResolve(Collection<Path> files, Set<String> passes);
  ValidationResult propose(Path file, String blockId, String text, String rationale, String source);
  void revert(Path file, String blockId);
  ValidationResult validate(Path file);
}
```

Svaki bundle se može razumjeti i testirati zasebno: core ne zna za SWT ni JDT, jdt ne zna za crtanje, ui ne implementira algoritme.

---

## 4. Model i hibridna inicijalizacija (`core`)

### 4.1 Ulazi (`merge.git`)
- `ours` = stage 2, `theirs` = stage 3, `base` = stage 1, čitani iz `DirCache` uz iste filtre kao checkout (EOL, autocrlf, `.gitattributes`). Encoding iz `IFile.getCharset()`.
- **Base fallbackovi:**
  - stage 1 sadrži konfliktne markere (virtualni base iz `ort`/`recursive`) → pravi merge-base (`RevWalk`, `RevFilter.MERGE_BASE` između HEAD i MERGE_HEAD / CHERRY_PICK_HEAD / REVERT_HEAD / rebase commita);
  - stage 1 ne postoji (add/add) → prazan base.
- **Rebase / cherry-pick / revert:** naslovi se prilagođavaju (vidi §8.2). `reversed=true` kod rebasea mijenja samo prikaz, tako da je lijevo uvijek „tvoje“.
- `W` = sadržaj working tree datoteke (git-ov rezultat s markerima) čuva se netaknut za Cancel.

### 4.2 3-way merge (port IntelliJ-a, Apache 2.0 uz atribuciju)
1. Line diff `base↔ours` i `base↔theirs`: JGit `HistogramDiff`, normaliziran prema ignore-whitespace policyju, s korekcijom stvarnom policyjem (kao IntelliJ `compareSmart` + `correctChangesSecondStep`).
2. `FairMergeBuilder`: presjeci nepromijenjenih raspona u base koordinatama daju jednake triplete; sve između postaje `MergeRange`.
3. `IgnoringChangeBuilder`: promjene samo u whitespaceu izdvajaju se kao zasebni mali rasponi.
4. Klasifikacija po IntelliJ tablici (`MergeRangeUtil.getMergeType`):

| Uvjet | Tip / strategija |
|---|---|
| base prazan, jedna strana prazna | INSERTED (druga strana), DEFAULT |
| base prazan, obje neprazne i jednake | INSERTED (obje), DEFAULT |
| base prazan, obje neprazne i različite | CONFLICT, NONE (kandidat za GREEDY prefiks/sufiks) |
| obje strane prazne | DELETED (obje), DEFAULT |
| obje jednake base (po policyju) | MODIFIED (samo whitespace), DEFAULT |
| jedna strana nepromijenjena | DELETED / MODIFIED (druga), DEFAULT |
| obje promijenjene identično | MODIFIED (obje), DEFAULT |
| inače | CONFLICT; strategija se određuje u resolver pipelineu (§5) |

### 4.3 Hibridna inicijalizacija rezultata
1. Tekst rezultata se gradi iz base-a: svaki DEFAULT blok dobiva tekst promijenjene strane → stanje `RESOLVED_BY_GIT`.
2. Pokreće se resolver pipeline (§5): riješeni konflikti → `AUTO_RESOLVED`, `needsReview=true`.
3. Preostali konflikti u rezultatu drže **base tekst** → `UNRESOLVED`.
4. **Poravnanje s W:**
   - W se parsira u segmente: čisti tekst i hunkovi markera (`<<<<<<<`, opcionalni `|||||||` diff3, `=======`, `>>>>>>>`; duljina markera prema `conflict-marker-size`).
   - Line diff između kandidata rezultata (konflikti zamijenjeni sentinelima) i W-a (hunkovi zamijenjeni sentinelima) poravnava blokove.
   - Gdje W ima čisti tekst, a mi konflikt (rerere, rename heuristike) → **git pobjeđuje**: tekst iz W, stanje `RESOLVED_BY_GIT`.
   - Gdje se čisti dijelovi razlikuju → koristi se W, a blok se označava.
   - Gdje W ima markere, a mi smo auto-riješili → ostaje naša rezolucija (`AUTO_RESOLVED`).
5. **Fallback** (poravnanje ne uspije, npr. stageovi nedostupni): model se gradi iz W-a SmartGit načinom. Konfliktni hunkovi postaju blokovi (ours/theirs iz markera, base iz diff3 sekcije ako postoji), a bočni paneli prikazuju stage 2/3 ako postoje.
6. Rezultat se upisuje u **shared dokument** (§7.1); disk se ne mijenja do Save.

### 4.4 Ponašanje tijekom uređivanja
- Svaki blok ima `Position` u dokumentu rezultata, s vlastitim position updaterom koji imitira IntelliJ `updateRangeOnModification` (pomak, rast, umetanje na rub).
- Ručna izmjena unutar raspona → `EDITED` (osim DELETED bloka čiji raspon postane prazan, koji postaje riješen) i gašenje auto-rezolucije tog bloka.
- Uvjet za auto-rezoluciju bloka: `state ∈ {UNRESOLVED}` i tekst raspona je jednak `baseText`.
- Re-diff prikaza (inner-line, spojnice) radi uz debounce od 500 ms u Eclipse `Job`u. Zastarjeli rezultati odbacuju se po brojaču izmjena dokumenta.
- Svaka operacija (Take, Ignore, Revert, auto-resolve, AI prijedlog) je **jedan compound undo korak**, a undo/redo vraća i stanja blokova (snapshot stanja u `IUndoableOperation`).

---

## 5. Auto-rezolucija (`core` + `jdt`)

### 5.1 Pipeline (redoslijed; svaki prolaz radi samo na nerazriješenim blokovima)

| # | Prolaz | Rješava | Default |
|---|---|---|---|
| 0 | Nekonfliktne promjene | promjena na jednoj strani ili identična na obje | uvijek → `RESOLVED_BY_GIT` |
| 1 | Java importi | konflikti u import bloku (§5.3) | uključeno (samo Java) |
| 1a | `.properties` i `MANIFEST.MF` (6c) | po ključu / zaglavlju / klauzuli; isključivi: što ne odluče ostaje konflikt | uključeno (sklopka po resolveru) |
| 1b | doprinosi EP-a `net.aftergit.merge.resolvers` | od 6c: JDT member-level (`merge.jdt`, id `members`) | uključeno (sklopka po resolveru) |
| 2 | Simple (riječi) | obje strane mijenjale isti redak, ali ne iste riječi | uključeno |
| 3 | Greedy | preklapajuća brisanja, umetak koji je prefiks/sufiks drugog | **isključeno**; kad je uključeno, blokovi su uvijek „za pregled“ |

Pipeline se veže uz ime datoteke (`ResolverPipeline.forFile`); resolver kaže za koje datoteke radi (`ConflictResolver.appliesTo`); detalji: `docs/design/2026-10-15-resolvers-design.md`.

### 5.2 Simple i Greedy (port IntelliJ `MergeResolveUtil`)
- **Tokenizer riječi** (IntelliJ `getInlineChunks`): nizovi identifikatorskih znakova su jedna riječ, svaki drugi znak koji nije razmak je zasebna riječ.
- **SimpleHelper(l, b, r, policy):**
  - 3-way merge na razini riječi (isti `FairMergeBuilder` nad riječima);
  - ako bilo koji word-fragment ima tip CONFLICT → `null`;
  - inače se spaja: u razmacima jednakima na sve tri strane uzima se base, inače strana koja se promijenila.
  - Prvo se pokušava s DEFAULT policyjem, zatim s IGNORE_WHITESPACES.
- **Greedy:**
  - klasteri preklapajućih fragmenata se spajaju; brisanja se uniraju;
  - ako je jedan umetak prazan, uzima se drugi; ako su jednaki po policyju, uzima se kraći;
  - **proširenje:** ako je jedan umetak prefiks/sufiks drugog, uzima se dulji;
  - inače `null`.
- Ograničenje: kod premašene veličine diffa (`DiffTooBig`) blok ostaje nerazriješen.
- **Složeni tokeni (AfterGit):** riječi spojene s `.`, `-` ili `:` bez razmaka (verzija `6.6.0`, kvalificirano ime `a.b.C`, koordinate `g:a:v`) su jedna cjelina: ako su ih obje strane promijenile različito, Simple i Greedy daju `null`, a rezultat ne smije sadržavati složeni token koji nema nijedna strana.
- Testovi: port `MergeResolveUtilTest` / `ComparisonMergeUtilTest` + property testovi (§10).

### 5.3 Java importi (`jdt`)
- Import blok svake strane = raspon `CompilationUnit.imports()` (JDT `ASTParser`). 3-way merge se dijeli na `[prije | importi | poslije]`, tako da fragmenti ne prelaze granicu.
- Skup importa rezultata:
  `(base ∖ obrisano_u_ours ∖ obrisano_u_theirs) ∪ dodano_u_ours ∪ dodano_u_theirs` (posebno obični, posebno statički).
- Nakon svake promjene tijela (Take, auto-rezolucija, AI) `ImportRewrite` nad working copyjem rezultata:
  - dodaje importe koji nedostaju, a koriste se u tijelu (kandidati iz ours/theirs);
  - uklanja importe koji su postali neiskorišteni **zbog merge-a** (postojeće neiskorištene ne dira);
  - poredak i grupiranje slijede Organize Imports postavke projekta.
- Import blokovi se označavaju `AUTO_RESOLVED (imports)`, s revertom.

### 5.4 Provjera kompajliranja auto-rezolucija (`jdt`)
- Pri otvaranju se u pozadini jednom reconcilaju ours i theirs (`WorkingCopyOwner` working copy s pravim classpathom) i bilježe njihove greške po redcima.
- Problem anotacije rezultata mapiraju se na blokove (`MergeBlock.diagnostics`).
- Ako u `AUTO_RESOLVED` / `AI_RESOLVED` bloku postoji greška koje nema u odgovarajućem dijelu ours ni theirs → oznaka **„auto-riješeno, ali ne kompajlira“**.

### 5.5 Resolve All Simple Conflicts (više datoteka, bez editora)
- Isti pipeline bez UI-ja, nad selekcijom, projektom ili repozitorijem; radi kao Eclipse `Job` s progressom i može se prekinuti.
- Datoteka se zapisuje samo ako je barem jedan konflikt riješen. **Nerazriješeni konflikti zapisuju se kao git markeri** (u stilu repozitorija: `merge.conflictStyle` merge ili diff3).
- Na kraju dijalog: „N potpuno riješeno · M djelomično · K netaknuto“, uz:
  - **Stage resolved files** (`git add` samo potpuno riješenih);
  - otvaranje preostalih u editoru;
  - **Undo Resolve All**: vraća originalni sadržaj svih datoteka, koji se čuva do zatvaranja dijaloga ili sljedećeg pokretanja.
- Java import prolaz i validacija rade i ovdje ako je JDT dostupan; datoteka s „ne kompajlira“ blokovima broji se kao djelomično riješena.

---

## 6. UI (`ui`)

### 6.1 Domaćin
- `MergeEditorInput extends CompareEditorInput`. `findContentViewer()` vraća `MergeViewer` (vlastiti `Viewer` s cijelim 3-pane compositeom).
- Naslov taba: „`Calc.java` — Resolve Conflict“.
- Dirty, Save, zatvaranje i `CompareUI.openCompareEditor` dolaze iz Compare frameworka.
- Toolbar Compare editora se ne koristi za naše akcije; naš toolbar je unutar composita, da raspored bude kontroliran.

### 6.2 Raspored (SmartGit, the SmartGit reference notes (kept privately, not in this repository) §2, §4–§5)
```
┌ Toolbar ─────────────────────────────────────────────────────────────────────────────────┐
│ ↑ ↓Next Conflict ↑ ↓ │ ◂Take Left ▸Take Right ◂▸▾ × │ ★Auto-resolve▾ │ ▥ ▥ ▥ ▥   ⋯ [✓ Save & Mark Resolved] │
├───────────────────────────────────────────────────────────────────────────────────────┤
│ main ("ours", 35cdd917)  │       │ Result — Calc.java      │       │ feature ("theirs") │
│ stripe │ gutter │ JDT RO │ 70px  │ ann.│gutter│ JDT editor │ 70px  │ JDT RO │ stripe    │
├───────────────────────────────────────────────────────────────────────────────────────┤
│ 2 konflikta · 3 auto-riješeno (za pregled) · 1 greška                                      │
└───────────────────────────────────────────────────────────────────────────────────────┘
```
- Toolbar i završetak prema `docs/design/2026-10-15-ui-polish-design.md` §1 (varijanta C): jedan red, naše ikone (svijetli i tamni set), rjeđe akcije u „⋯“, glavni gumb **Save & Mark Resolved** desno; donja traka nosi samo status i napomene.
- Stupci `pane | 70px spojnica | pane | 70px spojnica | pane`; paneli su jednake širine.
- Layouti: **All**, **Left + Result**, **Result + Right**, **Result Below** (SashForm: gore L | R bez spojnice, dolje rezultat). Odabir se pamti.
- **Zajednički vertikalni scrollbar** s virtualnim blokovima (SmartGit `amY`): visina bloka je maksimum preko panela, sidro je sredina viewporta, linearna interpolacija; padding redaka nema. Vertikalni scrollbarovi viewera su skriveni; horizontalni su po panelu, uz proporcionalnu sinkronizaciju.
- **Header** svakog panela je read-only polje s naslovom (§8.2).
- **Base** nije stalni panel:
  - hover nad konfliktnim blokom prikazuje base tekst;
  - „Compare with Base ▾“ otvara standardni Eclipse 2-pane compare (ours↔base, theirs↔base, ours↔theirs) preko `CompareUI`.

### 6.3 Paneli
- **Bočni (ours, theirs):** read-only `SourceViewer`.
  - Java: `JavaSourceViewerConfiguration` (leksičko bojanje).
  - Ostali jezici: tm4e `TMPresentationReconciler` ako postoji gramatika, inače običan tekst.
  - Eclipse `LineNumberRulerColumn`; traka pregleda 8px uz vanjski rub.
- **Srednji (rezultat):** JDT editor (§7) s annotation rulerom (ikone grešaka i upozorenja), brojevima redaka i trakom pregleda (blokovi + greške).
- Pozadina, trenutni redak, selekcija i font dolaze iz Eclipse postavki (*General → Editors → Text Editors*, *Colors and Fonts*).

### 6.4 Prikaz blokova
Sve boje su Eclipse theme `colorDefinition`. Kategorija: „AfterGit Merge“; default su SmartGit vrijednosti, s dark override CSS-om.

| Stanje | Bočni paneli | Rezultat | Spojnica |
|---|---|---|---|
| `UNRESOLVED` (konflikt) | conflict light, jake riječi (bez riječi: conflict strong) | conflict light, jake riječi | conflict light ispuna + conflict strong obris |
| `TAKEN_LEFT` / `TAKEN_RIGHT` / `TAKEN_BOTH` (konflikt) | preuzeta strana modified light, nepreuzeta siva (dropped light, precrtan dropped text, isprekidan rub) | modified light; u ruleru traka taken strong i ◀ / ▶ / ◆ | preuzeta modified strong ispuna, nepreuzeta isprekidan obris |
| `EDITED` (konflikt) | obje sive (kao nepreuzeta) | modified light; u ruleru traka edited strong i ✎ | obje isprekidan obris |
| `AUTO_RESOLVED` | strana koja je dala rezultat auto light (riječi auto hatch), druga siva | auto light; dok čeka pregled šrafura auto hatch (samo u dijelovima retka bez teksta) + točkasti rub auto strong; u ruleru traka auto strong, ◀ / ▶ / ◆ po tekstu i značka „A“ (puna / obrubljena) | auto light ispuna, nepreuzeta isprekidan obris |
| jednostrana izmjena, `RESOLVED_BY_GIT` | light vrste izmjene (added / modified / removed), jake riječi | ista ispuna na retcima rezultata | light ispuna vrste |
| `IGNORED` | light vrste izmjene | bez ispune | samo obris |
| `AI_RESOLVED` | točkasta linija | AI boja + ✨ + obrazloženje u hoveru, ↶ / ✓; u ruleru ◀ / ▶ samo kad je tekst jednak strani | točkasti obris u AI boji |
| + „ne kompajlira“ | — | crveni obrub (boja greške iz Eclipse anotacija), ima prednost pred točkastim rubom pregleda | — |

Trenutni blok (blok pod kursorom panela s fokusom, bilo koje stanje) dobiva okvir od 2 px i traku od 5 px u boji `current` u svakom panelu, nastavak okvira rubovima obiju spojnica i obris oznake na trakama pregleda. Ostaje trenutni dok je kursor na njegovim retcima (prazan blok: na retku njegove oznake). Detalji: `docs/design/2026-10-16-block-states-design.md`.

Inner-line razlike za nerazriješene blokove:
- 3-way na razini riječi (IntelliJ `compareThreesideInner`) kao SmartGit strong pozadina;
- primjenjuju se preko `ITextPresentationListener` (obrazac Eclipseovog `ChangeHighlighter`), tako da JDT bojanje ostaje.

**Default boje (light):**
- modified #FFDF99 / #FFEEC4
- added #BFE8BF / #EDF8ED
- removed #FF9999 / #FFEBEB
- conflict #F28B7A / #FBCDC5
- auto-resolved #1F9D8B / #D9F2EE, šrafura #C3E9E2
- preuzeto #E0A800 (traka i ◀ / ▶ / ◆ u ruleru)
- nepreuzeto #9A9A9A (tekst) / #F1F1F1
- ručno uređeno #6B6B8A (traka)
- trenutni blok: boja odabira platforme
- AI-resolved #834DF0 / #EFE6FF (IntelliJ AI boja)

**Dark:**
- modified #7A5100 / #403822
- added #1E601E / #2A442A
- removed #800000 / #4D3A3A
- conflict #8C2818 / #5C342E
- auto-resolved #3CC2AD / #21423E, šrafura #2A524C; AI kao dosad
- preuzeto #C9960A
- nepreuzeto #777777 / #333333
- ručno uređeno #9696BE
- trenutni blok #5896F0

Pragovi kontrasta (testovi `DarkThemeTest`, `LightThemeTest`): tekst na light ≥ 4.5, tekst na strong ≥ 3.0 (dark), trake i obrubi ≥ 3.0 (dark).

### 6.5 Spojnice
- `Canvas(SWT.DOUBLE_BUFFERED)` širine 70px. Svaki blok je SmartGit Bézier traka: ravno kroz stupce gumba, pa kubna krivulja s kontrolnim točkama na horizontalnoj sredini (`Path.cubicTo`, antialias).
  - Ispuna svijetlom bojom vrste; obris samo za otvoreni konflikt (conflict strong) i za `IGNORED` (bez ispune).
- Gumbi u spojnici (stupci po 10px):
  - `»` / `«` = Take;
  - `×` = Ignore;
  - **Ctrl+klik** na Take razrješava cijeli konflikt;
  - drugi Take sa suprotne strane konflikta **dodaje ispod** (IntelliJ append).
- Kotačić miša nad spojnicom skače na prethodnu/sljedeću promjenu.

### 6.6 Akcije
| Akcija | Ponašanje |
|---|---|
| Take Left / Take Right | zamijeni raspon bloka u rezultatu tekstom strane; stanje `TAKEN_*` |
| Take Left+Right / Right+Left | obje strane redom; `TAKEN_BOTH` |
| Ignore | konflikt: označi stranu kao obrađenu; nekonfliktni blok: zadrži tekst rezultata |
| Revert block | vrati `baseText` i stanje `UNRESOLVED` |
| Auto-resolve ▾ | Re-run (svi prolazi) · Run greedy (jednokratno) · Revert all auto-resolved |
| Next/Prev Conflict (svi konflikti, bilo koje stanje), Next/Prev Unresolved, Change, Auto-resolved, Error | navigacija; cilj postaje trenutni blok. U toolbaru „◀ Conflict ▶“ i „◀ Unresolved ▶“: dvije strelice oko jedne zajedničke riječi, par ide u „⋯“ samo cijeli |
| Compare with Base ▾ | 2-pane Eclipse compare |
| Layout | All / L+Res / Res+R / Res below |
| ⚙ | ignore whitespace, greedy on/off, inner-line on/off, pokretanje auto-rezolucije pri otvaranju (default on) |

**Prečaci:** context `net.aftergit.merge.scope`, podesivo u *Preferences → Keys*.
- Ctrl+. / Ctrl+, za Next/Prev Change;
- Ctrl+Alt+. / Ctrl+Alt+, za Next/Prev Conflict;
- Ctrl+Shift+. / Ctrl+Shift+, za Next/Prev Unresolved Conflict;
- Ctrl+Alt+← / Ctrl+Alt+→ za Take Left / Take Right;
- Ctrl+Alt+B za Take Both (lijevo pa desno);
- Ctrl+Alt+I za Ignore (samo u resolveru);
- Ctrl+Alt+1 … Ctrl+Alt+4 za layoute.

Sve akcije su komande kategorije „AfterGit“ s opisom (General › Keys); postavke, boje, font i postavke projekta: `docs/design/2026-10-16-preferences-design.md`.

### 6.7 Status i završetak
- Status: lijevo položaj („Conflict 3 of 7 · Take Left ◀“) dok je trenutni blok konflikt, zatim „2 konflikta · 3 auto-riješeno (za pregled) · 1 greška“.
- Kad nema nerazriješenih blokova ni „ne kompajlira“ oznaka → zeleni bar „✓ Sve riješeno“; **Save & Mark Resolved** je glavni gumb toolbara.
- „Za pregled“ se ne mora potvrditi prije spremanja, ali se broji u statusu. Akcija „Mark reviewed“ po bloku ili za sve uklanja oznaku.

---

## 7. JDT integracija (`jdt`)

### 7.1 Dokument rezultata
- `ITypedElement` za workspace `IFile` implementira `ISharedDocumentAdapter`, pa je dokument iz `JavaUI.getDocumentProvider()`. To je isti buffer i JDT working copy kao u Java editoru te datoteke.
- Hibridni rezultat se upisuje u taj buffer; otvoreni Java editor vidi isto stanje.
- Na disk ide samo na Save. Cancel vraća W u buffer i odbacuje dirty stanje.

### 7.2 Puni editor u srednjem panelu
- `JdtResultEditorAdapter`: privatna podklasa `CompilationUnitEditor` (interni JDT API, x-friends). Isti obrazac kao Eclipseov `JavaMergeViewer`:
  1. `createJavaSourceViewer(...)` u našem parentu;
  2. ubacivanje viewera u `AbstractTextEditor.fSourceViewer` refleksijom;
  3. `init(site, FileEditorInput)`;
  4. izričit `installSemanticHighlighting()`;
  5. folding isključen.
- Dobivamo reconciler, squiggles, annotation model, hover, Ctrl+1 (`JavaCorrectionAssistant`), Ctrl+Space i semantičko bojanje.
- Aktivira se context `org.eclipse.jdt.ui.javaEditorScope` dok srednji panel ima fokus.
- **Degradirani način** (adapter baci iznimku ili interne klase nedostaju):
  - `SourceViewer` + `JavaSourceViewerConfiguration` + vlastiti debounced `ICompilationUnit.reconcile(AST.getJLSLatest(), true, owner, monitor)` u `IAnnotationModel` + `SourceViewerDecorationSupport` + `MarkerAnnotationPreferences`;
  - ostaju bojanje i greške, bez Ctrl+1 i Ctrl+Space;
  - diskretna obavijest u statusu + log.
- Sav interni API je u jednoj klasi i pokriven je testom koji pada pri promjeni Eclipse verzije.

### 7.3 Bočni paneli
`JavaSourceViewerConfiguration(JavaUI.getColorManager(), chainedStore(project), null, IJavaPartitions.JAVA_PARTITIONING)` + `JavaTextTools.setupJavaDocumentPartitioner`. Ulančani store: projektni jdt.core → JDT UI → JavaCore → EditorsUI. Semantičke boje povrh leksičkih: `SideSemanticColors` (faza 2, §11).

### 7.4 Dretve
- Engine re-diff: Eclipse `Job` uz debounce od 500 ms.
- Reconcile: JDT reconciler dretva.
- UI ažuriranje: samo `Display.asyncExec`, uz odbacivanje zastarjelih rezultata.

---

## 8. Git / EGit integracija (`git`, `ui`)

### 8.1 Komande
- **Resolve in AfterGit…** (`net.aftergit.merge.commands.resolve`, ikona `icons/resolve.png`):
  - Team izbornik odmah ispod EGitovog Merge Toola, izbornik i toolbar Git Staging viewa (kontekstni izbornik tog viewa EGit ne otvara drugima), Package i Project Explorer;
  - vidljivo kad je odabrana jedna konfliktna datoteka (vlastiti property tester: `DirCache` stage > 0);
  - EGitov Merge Tool ostaje netaknut; opcija u njegovom dijalogu, stari unos Team → Merge Tool i gumb u dijalogu nakon rebasea nisu mogući bez zakrpe EGita.
- **Resolve All Simple Conflicts** (`net.aftergit.merge.commands.resolveAll`): Team izbornik i izbornik Git Staging viewa; selekcija, projekt ili repozitorij.

### 8.2 Naslovi panela
| Operacija | Lijevo | Desno |
|---|---|---|
| merge | `<HEAD branch> ("ours", <sha8>)` | `<MERGE_HEAD ref/branch> ("theirs", <sha8>)` |
| rebase | `Rebase target '<onto>' (<sha8>)` | `Rebased commit '<subject>' (<sha8>)` |
| cherry-pick | `<HEAD branch> ("ours")` | `Cherry-picked '<subject>' (<sha8>)` |
| revert | `<HEAD branch> ("ours")` | `Revert of '<subject>' (<sha8>)` |

Srednji panel: „Result — <ime datoteke>“.

### 8.3 Save i Mark Resolved
- **Save:** zapisuje dokument (originalni EOL i encoding).
  - Ako postoje `UNRESOLVED` blokovi, pita: *zapiši ih kao git markere* (default; datoteka ostaje konfliktna) ili *zadrži trenutni tekst (base)*.
- **Save & Mark Resolved** (glavni gumb toolbara):
  - bez otvorenih konflikata: spremi, `git add` preko JGita (uz osvježavanje EGita), zatvori resolver; statusna traka: „Calc.java resolved and staged“ (+ broj nepregledanih auto-riješenih blokova, koji akciju ne blokiraju); ako je resolver u međuvremenu ponovno izmijenjen, ostaje otvoren (stageano je spremljeno stanje) i statusna traka to kaže;
  - s otvorenim konfliktima dijalog: *Cancel* (default), *Save without staging* (kao Ctrl+S, editor ostaje), *Save with markers* (markeri u stilu `merge.conflictStyle`, bez stageanja, resolver se zatvara);
  - neuspjelo spremanje ne stagea ništa i editor ostaje dirty; neuspjeli `git add` ostavlja datoteku spremljenu i editor otvoren;
  - Ctrl+S samo sprema.
- **Cancel / zatvaranje bez spremanja:** vraća W; na disku ništa ne ostaje.

### 8.4 Vrste konflikata u v1
| Konflikt | Ponašanje |
|---|---|
| sadržaj tekstualne datoteke (uključujući add/add) | 3-pane editor |
| modify/delete, delete/modify, binarne | dijalog **Keep ours / Keep theirs / Delete** (`checkout --ours/--theirs` + add, ili `git rm`) |
| submodul, rename/rename, symlink | poruka + ponuda EGit Merge Toola |

### 8.5 Granice veličine
- Iznad 20 000 redaka ili kod premašenog diff budžeta: inner-line i greedy isključeni, napomena u statusu.
- Editor i Take/Ignore rade i dalje.

---

## 9. Greške i rubni slučajevi
- Stageovi nečitljivi ili repozitorij nedostupan → fallback na parsiranje W-a (§4.3.5) + upozorenje.
- W promijenjen izvana dok je editor otvoren → standardni Eclipse „file changed“ tok preko shared dokumenta. Model se ponovno inicijalizira tek ako korisnik prihvati.
- Nekonzistentni krajevi redaka → interno se normalizira, a zapisuje se stilom W-a. Ako W ima miješane krajeve, pita se.
- Iznimka u resolveru → blok ostaje `UNRESOLVED`, greška ide u log, ostali prolazi se nastavljaju; od 6c ne nakon
  ekskluzivnog (formatskog) resolvera: njegov blok ostaje konflikt i ne nudi se tekstualnim prolazima.
- JDT nedostupan (ne-Java projekt) → nema import prolaza ni validacije; sve ostalo radi.

---

## 10. Testiranje
1. **Engine (JUnit 5):**
   - port IntelliJ `ComparisonMergeUtilTest`, `MergeResolveUtilTest`, `LineComparisonMergeUtilTest` (uz atribuciju);
   - testovi klasifikacije, parsera markera (merge/diff3, različite duljine markera), hibridnog poravnanja s W-om (rerere slučaj, rename slučaj), fallbacka.
2. **Property testovi:**
   - simetrija `resolve(l,b,r) ≡ mirror(resolve(r,b,l))`;
   - idempotentnost pipelinea;
   - `EDITED` blokovi se nikad ne mijenjaju;
   - nekonfliktni dio rezultata je jednak git-ovom (`git merge-file`) za iste ulaze.
3. **Korpus:**
   - skripta ponavlja stvarne merge-ove iz povijesti otvorenih repozitorija (JGit, EGit, intellij-community podskup);
   - metrike: % auto-riješenih konflikata, % koji kompajliraju, broj odstupanja od git-a u čistim dijelovima (mora biti 0 nakon poravnanja);
   - uključuje scenarij iz eseth.org članka i `Calc.java` demo.
4. **JDT:** importi (dodani na obje strane, obrisani, statički, isti import, wildcard), oznaka „ne kompajlira“, degradirani način (simulirani kvar adaptera).
5. **Git:** JGit test repozitoriji za merge, rebase, cherry-pick, revert, modify/delete, add/add; naslovi, swap, Save s markerima, `git add`, Resolve All + Undo.
6. **UI (SWTBot):** otvaranje, Take / Ignore / append / Ctrl+klik, Revert, undo/redo sa stanjima, Save, Save & Mark Resolved, Cancel vraća W, promjena layouta, dark tema.
7. **Vizualna usporedba:** snimke vs the SmartGit reference screenshots (kept privately, not in this repository) po checklisti (širina spojnice 70px, Bézier oblik, boje, trake pregleda, gutter).

---

## 11. Faze
- **v1:** §3–§10.
- **Faza 2:**
  - ugrađeni **MCP server** (detaljno: `docs/design/2026-10-15-mcp-server-design.md`): opcionalni bundle `net.aftergit.merge.mcp` i feature, isključen po defaultu i nevidljiv dok je isključen (osim stranice postavki i unosa u General > Startup and Shutdown); vlastiti mali server na JDK `HttpServer` + Gson (bez Jettyja i bez MCP SDK-a u runtimeu; SDK klijent samo u testu konformnosti), 127.0.0.1, token u secure storageu, provjera `Origin`, ograničenja veličine (tijelo 1 MiB, prijedlog 200 000 znakova, datoteka 2 MiB), „Copy MCP configuration“. Alati: `list_conflicts`, `get_conflict`, `validate`, `auto_resolve`, `propose_resolution`, `revert_block` (briše samo prijedlog pozivatelja) — samo prijedlozi, nema alata koji sprema ili stagea. Prijedlozi se odmah primjenjuju na nedirnute otvorene konflikte, inače čekaju („Show the waiting proposal“), a za vrijeme spremanja i dijaloga se drže. Temelji se na `ConflictResolverService` u tom bundleu;
  - „Resolve with AI“ (detaljno: `docs/design/2026-10-16-resolve-with-ai-design.md`): API u `merge.core` (`net.aftergit.merge.core.ai`), EP `net.aftergit.merge.aiResolver` u `merge.ui`, opcionalni bundle `net.aftergit.merge.ai` i feature (Anthropic Messages API, OpenAI-compatible Chat Completions; JDK `HttpClient` + Gson), isključen po defaultu i nevidljiv dok nijedan resolver nije spreman; jedan zahtjev po bloku, odgovor je prijedlog kao kod agenata (`AI_RESOLVED`, needs review, jedan undo korak), ništa se ne sprema; privola prije udaljenog servera (pamti se po odredištu, provjerava prije svakog zahtjeva), ključ u secure storageu, ograničenja 200 000 znakova. Copilot može doći kroz isti EP;
  - JDT member-level resolver, `.properties` i `MANIFEST.MF` resolveri (gotovo u 6c, `docs/design/2026-10-15-resolvers-design.md`);
  - Structure Compare panel (gotovo u 6d: `docs/design/2026-10-16-structure-compare-design.md`; stupac lijevo od panela, skriven po defaultu, Ctrl+Alt+S, i Outline pogled);
  - semantičko bojanje bočnih panela (gotovo): `SideSemanticColors` u `merge.jdt` (samo javni JDT API: `ASTParser` s bindinzima iz projekta datoteke, tekst strane kao sadržaj) boji polja, varijable, parametre, pozive, zastarjele članove, tipove itd. kao Java editor, po Java > Editor > Syntax Coloring i temi, uživo; računa se jednom po strani u pozadini, primjenjuje spajanjem u prezentaciju pogleda (povrh leksičkih boja); bez Java projekta samo deklaracije; iznad 1 000 000 znakova ništa; prekidač Preferences > AfterGit „Semantic colours in the side panes“ (uključen po defaultu). `merge.ui` ga dobiva samo kroz `JavaPaneSetup`.
- **Faza 3:** svi jezici preko LSP-a (lsp4e dijagnostika); samostalni CLI / `git mergetool` / MCP server bez Eclipsea nad `merge.core`. Dio 1 (sintaksne boje svih jezika kroz tm4e, opcionalni bundle `net.aftergit.merge.lsp`) je ugrađen; vidi `docs/design/2026-10-16-languages-design.md`. Dio 2 (greške language servera u rezultatu kroz lsp4e, ours i theirs provjereni za „nove greške“, Save & Mark Resolved i Resolve All) je ugrađen.

**AI sigurnosna pravila (vrijede od faze 2, model ih podržava od v1):**
- AI prijedlog je uvijek `AI_RESOLVED` + `needsReview`, uz JDT validaciju;
- AI nikad ne sprema niti radi `git add` bez izričite postavke (default off);
- svaki AI potez je jedan undo korak i ima revert po bloku.

---

## 12. Licence i atribucija
- Portani IntelliJ kod (intellij-community, Apache 2.0) zadržava JetBrains copyright header; NOTICE datoteka u `merge.core`.
- SmartGit: preuzima se samo ponašanje i izgled opisan u the SmartGit reference notes (kept privately, not in this repository); **nema koda ni ikona** iz SmartGita. Ikone se izrađuju nove.
- Eclipse obrazac `JavaMergeViewer` (EPL-2.0) se reimplementira, ne kopira; ako se kopira, uz EPL header.

## 13. Otvorena pitanja (rješavaju se u planu ili implementaciji)
- Točan algoritam poravnanja s W-om kod jako divergentnih slučajeva: prag kad se ide na fallback.
- Tamne varijante auto/AI boja: provjera kontrasta na Eclipse dark temi.
- Rade li `JavaCorrectionAssistant` i content assist ispravno kad se dokument mijenja programski (Take) tijekom otvorenog popupa; po potrebi zatvoriti popup prije operacije.
