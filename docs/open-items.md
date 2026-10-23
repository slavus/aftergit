# AfterGit: što je još otvoreno

Stanje: 2026-10-17, main ab08b67.

Jedno mjesto za sve što nije gotovo, odgođeno ili neprovjereno. Izvori: `docs/design/*.md`, `docs/INSTALL.md`,
`docs/testing.md`, `docs/visual-checklist.md` te bilješke i pregledi pojedinih planova (plan 6a do 6d, block-states,
staging, semantic, preferences, languages Dio 1 i Dio 2). Stavka s „(provjeriti)“ nije potvrđena kao otvorena ni kao
zatvorena.

## 1. Neizrađene funkcionalnosti

Faza 3, jezici (`docs/design/2026-10-16-languages-design.md`):
- Dio 3: Structure panel i Outline iz `textDocument/documentSymbol` za datoteke bez ugrađenog locatora
  ([Part 3](design/2026-10-16-languages-design.md#part-3--structure-panel-and-outline-from-document-symbols)).
- Dio 4: editor tipa datoteke u rezultatu, ugrađen kao Java editor, uz degradirani način
  ([Part 4](design/2026-10-16-languages-design.md#part-4--the-file-types-editor-in-the-result)).
- Dio 5: strukturirani resolveri za JSON (Gson), YAML (SnakeYAML) i XML (StAX), po jedan prekidač za format
  ([Part 5](design/2026-10-16-languages-design.md#part-5--structured-resolvers-for-json-yaml-and-xml)).

Ostatak faze 3 (`docs/design/2026-10-13-merge-resolver-design.md` §11):
- samostalni CLI nad `merge.core`;
- `git mergetool` ulaz bez Eclipsea;
- MCP server bez Eclipsea (danas je MCP ugrađen u `net.aftergit.merge.mcp` i traži radni Eclipse).

Projektirano ili spomenuto kao „kasnije“, a nije izrađeno:
- Resolve with AI: Copilot kroz EP `net.aftergit.merge.aiResolver` (nema javnog API-ja u Eclipseu)
  (`2026-10-16-resolve-with-ai-design.md`, Out of scope);
- Resolve with AI: streaming odgovora, slanje cijele datoteke, chat ili follow-up razgovor, trajno čuvanje
  tih prijedloga (isti odjeljak);
- Structure panel: akcije po članu (Take cijelog člana, opcija B) i klasični Eclipse structure diff (opcija C)
  (`2026-10-16-structure-compare-design.md`, Out of scope);
- resolveri: detekcija premještenih članova, drugi key-value formati (`.gitattributes`, `.editorconfig`;
  YAML i JSON pokriva Dio 5) (`2026-10-15-resolvers-design.md`, Out of scope);
- upozorenje „duplicate key after merge“ za čiste git spojeve koji dvaput dodaju isti ključ; tri `@Disabled`
  testa u `merge.core` su protuprimjeri `[6c T8]`;
- postavke: boje po jeziku, izvoz/uvoz AfterGit postavki mimo Eclipseova izvoza, timske postavke boja
  (`2026-10-16-preferences-design.md`, Out of scope);
- MCP: provjera prevođenja za jezike osim Jave na agentskom putu (vidi §3).

## 2. Otvorena pitanja dizajna

Iz `2026-10-13-merge-resolver-design.md` §13. Nijedno nije zatvoreno u dokumentima.
- Točan algoritam poravnanja s W-om kod jako divergentnih slučajeva: prag kad se ide na fallback.
- Tamne varijante auto/AI boja, kontrast na Eclipse dark temi (provjeriti: preferences je dodao tamne vrijednosti
  svih boja i testove kontrasta, ali pitanje nije formalno zatvoreno).
- Rade li `JavaCorrectionAssistant` i content assist kad se dokument mijenja programski (Take) uz otvoren popup;
  po potrebi zatvoriti popup prije operacije (provjeriti: za prijedloge agenata ponuda čeka dok je popup otvoren,
  `[6a final M10]`; za Take nije zapisano).

## 3. Odgođeno i poznata ograničenja

### Navedeno u zadatku, a pokazalo se riješenim ili drukčijim

- **Resolve with AI, opoziv privole: riješeno.** Gumb „Forget Remote Consents“ na stranici postavki briše sve
  zapamćene odgovore (INSTALL, odjeljak Resolve with AI; `[6b final M3]`). Nema stavke za otvoreno.
- **Tamna tema, bijeli ruler s brojevima redaka: riješeno.** Brojevi redaka su na pozadini teksta u boji
  brojeva redaka editora, i u tamnoj temi (commit `afac6c4`, ruling R7 u planu preferences; INSTALL ga navodi
  kao promjenu izgleda). Treba samo oko na stvarnoj tamnoj temi (vidi §4).

### Odgođeno

- **MCP agentski put provjerava samo Javu** (`AgentBridge.javaChecks` / `OwnModels`; moglo bi se `ResolveAllJob.checks`).
  Zašto: spec Dijela 2 imenuje samo editor, Save & Mark Resolved i Resolve All; širenje rezultata MCP alata je
  promjena sučelja za agente. Odlučeno: ruling u ledgeru languages Dijela 2, zapisano u status Dijela 2
  (`languages-design.md`) i INSTALL.
- **Greška tm4e-a pri postavljanju dokumenta** (`MergeViewer` `setInput` / `PaneReconciler.install`, tm4e-ov
  `inputDocumentChanged`) i dalje sprječava otvaranje resolvera. Zašto: rizični kod je tm4e-ov i dira isti put kao
  ugradnja editora. Odlučeno: završni pregled Dijela 1, planirano za Dio 4. Pokriven je samo put kroz
  `LspPaneSetup.presentationReconciler` (plain paneli + jedan WARNING).
- **Markdown nije obojen.** tm4e gramatika za Markdown ne prevodi se pod joni 2.2.7; gramatika se uopće ne učitava
  (`Grammars.MARKDOWN_SCOPE`), `.md` ostaje običan tekst. Odlučeno: Dio 1 (languages-design, „Status“).
  Povezano (provjeriti): jezici koji uključuju Markdown (F# `///`) učitavaju ga ipak, uz tm4e-ov ERROR po retku
  `[lsp1 T3 M2]`.
- **Staging osvježavanje za projekte koji nisu dijeljeni s Gitom.** Desni klik / dvoklik je popravljen
  (`47ed61c`, `ProjectRepositories`), samo osvježavanje popisa nije riješeno u stvarnoj instalaciji. U kodu:
  `IndexEvents` javlja EGit-ovoj instanci (`48015e7`), testovi uz isključeno automatsko dijeljenje
  (`StagingRefreshTest`); INSTALL tvrdnju „refreshes at once“ ne potvrđuje nijedna ručna provjera (red 33
  u `visual-checklist.md` nema zapisan ishod). Provjeriti i popraviti.
- **ESLint, Angular i Markdown server ne odgovaraju na virtualne dokumente.** Server koji ne odgovori pamti se kao
  tih do restarta Eclipsea (jedan INFO). Prva JS/TS provjera može čekati do 30 s (`FIRST_CHECK_MILLIS`, dok server
  učitava projekt); ispitano s WWD 1.2.7: ~63 s dok su dva tiha servera pitana redom, sada se pitaju istodobno.
  Odlučeno: završni pregled Dijela 2 i ruling druge runde. Cijena: server koji kasnije počne odgovarati ostaje
  preskočen do restarta.
- **Ponašanje servera po imenu datoteke** (sheme po imenu: `package.json`, `docker-compose.yml`; LemMinX Maven za
  `pom.xml`; `.d.ts`) može se razlikovati za virtualna imena (`<ime>.aftergit-<uloga>-<n><.ext>`). Greška koju
  donosi samo ime ne uokviruje blok, a okvir može počivati na grešci koju lsp4e ne prikazuje (hover i status ju
  imenuju). Odlučeno: ruling I3 završnog pregleda Dijela 2.
- **Točke refleksije u tm4e / lsp4e** za provjeru nakon svakog ažuriranja: popis i `javap` upute u
  `docs/testing.md`, odjeljak Maintenance (`TMPresentationReconciler`, `TMModelManager.models`, `TMModel.listeners`,
  `LanguageServerWrapper.fileBufferListener`, polje `val$resource` posla `LSPDiagnosticsToMarkers$2`, kopirane
  konstante markera). Neprovjerena pretpostavka: tm4e-ov partitioner se aktivira samo iz editora koji sam
  odbacuje model.
- **Commit `c08bf0b` sam za sebe ne prevodi** (`forgetClients` stiže tek u `98ac470`); cijela grana prevodi.
  Utječe samo na bisect preko tog para; povijest se ne prepisuje.
- **MCP `revert_block` briše samo prijedlog pozivatelja.** Agent ne može očistiti AfterGitove auto prijedloge,
  korisnik može `[6a ledger, ruling M3]`.
- **Newer version wins radi samo u opisnim datotekama** (pom/*.pom, MANIFEST.MF, SOURCE-MANIFEST.MF, feature.xml,
  category.xml, *.target, *.product, *.gradle(.kts), gradle.properties za ključeve s „version“, libs.versions.toml,
  package.json). Zašto: probe pregleda su pokazale pogrešne izbore u kodu. Cijena: manje auto rješenja drugdje.
  Isključen po defaultu.
- **Čist git spoj koji dvaput doda isti ključ** nije konfliktni blok, ostaje kako ga je git složio `[6c T8]`.
- **Member resolver ne djeluje kad je spoj izgrađen iz markera**; `.properties` i MANIFEST retci odvojeni samim CR
  čitaju se kao jedan redak; pozicijski inicijalizatori s različitim brojem `init:` jedinica ostaju konflikt
  (INSTALL, resolvers-design §1–§3).
- **Resolve with AI:** consent se provjerava prije svakog zahtjeva, ali čitanje postavki u `resolve()` može
  se promijeniti između provjere i zahtjeva (TOCTOU, parkirano `[6b T5]`); resolver bez `destination()` dijeli
  privolu po id-u `[6b T5 N4]`; za običan `http` izvan računala postoji samo upozorenje na stranici, ne u dijalogu
  privole `[6b final-rereview N3]`.
- **Dio 1:** tekst iznad 1 000 000 znakova, redak iznad 20 000 znakova (`MAX_LINE_CHARS`) i redak s umnoškom
  tokena i znakova iznad 10 000 000 (`MAX_LINE_COST`) ostaju obični. Tema se bira pri otvaranju datoteke.
  Zašto: SWT slaže redak ponovno za svaki raspon stila; mjereno u planu.
- **Dio 2:** `merge.lsp` traži lsp4e (`Require-Bundle`), pa instalacija bez lsp4e gubi i boje Dijela 1
  (ruling M7, INSTALL). Strane se provjeravaju u AfterGitovoj vlastitoj instanci servera (jedan dodatni proces po
  jeziku i projektu dok je resolver otvoren), jer lsp4e ne vraća dijagnostiku dokumenta bez resursa i editora.
  Commit rezultata ne primjenjuje lsp4e-ove izmjene pri spremanju (format on save, `willSaveWaitUntil`).
  Save & Mark Resolved upozorava samo u statusnoj traci (nikad dijalog), čeka do 2 s. Resolve All izostavlja
  server koji nije odgovorio, a editor tada broji njegove greške rezultata kao nove. Datoteka koja ne može biti
  shared buffer (BOM, `\r` u retku, nespremljen editor) nema greške servera u rezultatu. Kotlin i Python nemaju
  server u targetu.
- **Prozor kopija klijenta:** zahtjevi definicijskog klijenta koji registriraju nešto trajno (`*/watcher/*`)
  odgovaraju se praznim uspjehom; filtar je po imenu `[lsp2 final-rereview]`.
- **Staging i listeneri:** tri globalna listenera se nikad ne uklanjaju (`ConflictCache`, `AttentionMarkers.IndexWatch`,
  `ProjectRepositories`); jedan `stop()` hook prije izdanja `[staging M3/N3]`. Dvoklik na povezanu (linked)
  datoteku projekta bez dijeljenja je mrtav klik `[staging N5]`. Team izbornikov „Merge Tool“ uvijek otvara EGit,
  a Synchronize view ide kroz naredbu (nije testirano) (INSTALL).
- **Boje i teme:** „Frames“ su globalni, sheme po temi `[prefs final M13]`; konture ruba bloka u High contrast
  svijetloj shemi ispod 3:1 `[prefs T7 M4]`; boja odabira GTK-a poslije promjene teme u sesiji, Eclipse traži
  restart `[prefs T7 M3]`; dvosmislena prva migracija u tamnoj temi `[prefs T7 N1]`.
- **Tipkovnica:** Ctrl+Alt+B/I nadjačavaju AnyEdit / chat vezove unutar resolvera (odluka U3). Ctrl+Alt+. i
  Ctrl+Alt+, na Windowsu je AltGr; provjeriti na hrvatskom i njemačkom rasporedu prije izdanja `[states final M7]`.
- **Čeka odluku korisnika:** grupirana navigacija „◀ Conflict ▶ / ◀ Unresolved ▶“ umjesto četiri gumba i Ruling 19
  (na AI bloku koji čeka pregled nema ») `[states final M8]`; treba li Take ili tipkanje nad AI/agent blokom
  zaboraviti pohranjeni prijedlog (sada se nakon ponovnog otvaranja nudi opet) `[states final N2]`.
- **Resolve with AI / agenti:** naredbe bez spremnog resolvera ili prijedloga su tihi no-opovi `[prefs final]`.

## 4. Ručne provjere koje čeka korisnik

Redovi su iz `docs/visual-checklist.md`; ishod treba zapisati tamo.
- Resolve with AI s pravim API ključem (Anthropic) i lokalnim Ollamom: Test Connection, privola jednom, odgovori kao
  AI blokovi, u Error Logu jedan INFO po bloku bez sadržaja (red 26; redak 25 je stranica postavki).
- Pravi Claude Code protiv AfterGitova MCP servera: prijedlozi su AI blokovi, ništa spremljeno ni staged, gašenje
  prekida vezu; unos „AfterGit AI Agents“ u General › Startup and Shutdown i Security (red 22).
- Staging na stvarnoj instalaciji: desni klik → Merge Tool, Enter i dvoklik otvaraju AfterGit; dugme u alatnoj
  traci Staginga se vidi (inače ukloniti `visibleWhen`); datoteka prelazi u „Staged Changes“ odmah, i za projekt koji
  nije dijeljen (retci 17, 31, 33; vidi §3).
- Ostali ručni retci bez zapisanog ishoda: 18 i 19 (zatvaranje poslije Save & Mark Resolved), 32 (Open in EGit
  Merge Tool), 34 (markeri u Problems i overlay u Stagingu).
- GTK šav šrafure pri skrolanju (block-states Task 4; hatch faza je u klijentskim koordinatama).
- Nove postavke, sheme boja i Appearance u demu: sve prekidače, sheme Colour-blind safe i High contrast u svijetloj i
  tamnoj temi, pregled sheme, projektne postavke, tipke u Keys, boje u Colors and Fonts i migracija
  (retci 39 do 41); tamna tema: ruler s brojevima redaka.
- Teme drugih plug-ina (DevStyle, eclipsethemes, Spectrum, Moonrise, Planet) poslije tamne sesije `[prefs final M1]`.
- Jezici u demu (redovi 42 i 43): boje TypeScript, JSON, YAML, CSS, XML u svijetloj i tamnoj temi; u `web/cart.ts`
  greške servera, squiggle, oznaka u pregledu, okvir „does not compile“ i njegov hover/status.
- ESLint s pravom eslint bibliotekom u projektu, i YAML sa shemom (nije isprobano; stvarni WWD je isproban samo
  za TS, `.d.ts`, YAML bez sheme i Markdown).
- Semantičke boje bočnih panela: red 30, snimke pokrivaju samo redove 1 do 27 uzorka (`run()` s pozivom, autoboxing,
  `var`, record nisu viđeni) `[semantic M8]`.
- Struktura: red 35 do 38 na stvarnoj instalaciji, uključujući Outline pogled i čitač zaslona na redovima stabla
  (`[6d final I1]` je popravljen testom, ne ručno).
- Windows: AltGr tipke Ctrl+Alt+. i Ctrl+Alt+, na hrvatskom i njemačkom rasporedu (vidi §3).

## 5. Sitne primjedbe iz pregleda (MINOR, nisu popravljene)

Oznake: 6a = MCP server, 6b = Resolve with AI, 6c = resolveri, 6d = Structure compare, states = block-states,
staging = Merge Tool i Staging, semantic = semantičke boje strana, prefs = preferences, lsp1 = languages Dio 1,
lsp2 = languages Dio 2. T = task, M/N = oznaka nalaza.

### Core / resolveri
- Svaki Java body blok parsira cijelu datoteku šest puta; originale predmemorirati po prolazu: `BlockRegion` `[6c final M7]`.
- `regionLocal` čita originale i kad je bočni panel uređen (odbija, sigurno): `BlockRegion` `[6c T1 N1]`.
- Nepromijenjena klauzula koja samo dobiva zarez prelama se ako je redak već dulji od 72 bajta: `ManifestWriter.appendKept` `[6c final N1]`.
- Neisključivi duplikat člana: `MembersResolver` `[6c T4]`; do 3 WARNINGA po bloku `[6c T4]`.
- `.properties`: završni backslash u zadnjem retku, prazni redovi pripadaju sljedećem unosu, `key()` retka s
  usamljenim backslashem: `PropertiesStructure` / properties resolver `[6c T2 M1–M3]` (provjeriti).
- Manifest: test praznih redaka iznad zaglavlja liste, ista Bundle-Version kroz resolver, escape navodnici, nevaljane
  sekcije `[6c T3 M1–M3, M5]`.
- Java member resolver: predmemorija parsiranja `[6c T5 M3]`, enum konstanta s komentarom na kraju retka
  `[6c T5 M4]`, test annotation tipa, ASCII provjera `plugin.properties`, poplava WARNINGA po bloku `[6c T5]`.
- Nema end-to-end testa isključivanja resolvera `[6c T6]`.
- Brojači pokrivenosti u `JavaMembersPropertyTest`, „shared-add kind 0“ na dva udaljena mjesta `[6c T7 M1, M2]` (provjeriti).

### UI / editor
- `setSelectedBlock` poziva `redrawDecorations()` bez `isDisposed()` zaštite: `MergeViewer` `[states final N1]`.
- Tipkanje na kraju zadnjeg retka bloka: nema testa da `selectedBlock` ostaje `[states T6 M2, final M4]`.
- Cijena crtanja na velikim datotekama: `OriginColumn.at` skenira sve gutere (binarno pretraživanje),
  `BlockStyles.origins` gradi tekstove pri svakom osvježavanju, `PanePainter.paintHatch` alocira `Region` po retku
  `[states final M6, T4 M3]`.
- Riječ grupe u alatnoj traci nije zasivljena kad su obje strelice onemogućene: `MergeToolbar` `GroupWord`
  `[states final M7]`.
- Zastarjeli javadoc `LineStyle` (3 px traka), kontrast test ovisan o GTK-u, nema testa labele u `plugin.properties`
  `[states T2]`; boja „A“ u tamnom izrezu, jdt javadoc `[states T4 N1, N2]`; N1–N3 i M1/M3 djelomično `[states T6]`.
- Stari `resolvedBy` / pohranjeni prijedlog nakon Take: vidi §3 `[states final N2]`.
- Status redak kaže „auto-resolved“ za AI blok `[6a T7]` (provjeriti).
- Ponuda agenta zatvara korisnikov popup: riješeno; probe pri prioritetu 0 broji i hoverove (samo kasni ponudu
  250 ms): `Popups.open` `[6a final N3]`.
- `propose` odmah nakon starta servera dobiva grešku „stored and offered again“ iako se primijeni trenutak kasnije:
  `ViewerModel.agentsOn` `[6a final N2]`.
- Dvoklik na povezanu datoteku nije pokriven testom; negativna predmemorija repozitorija: popravljeno u `a9d3529`
  `[staging N5]`. Kanonizacija puta po događaju indeksa za svaki EGit repozitorij: `IndexEvents` `[staging N7]`.
- Dokumentirano, ne popravljeno: spremanje iz drugog editora ne ažurira markere, restart briše i „conflicts“ marker
  `[staging M4, M5]`.

### Struktura
- Cijelo stablo se gradi i širi pri svakom izračunu, i kad se ništa nije promijenilo (usporediti `nodes()`):
  `StructureTree.apply`, `StructureController` `[6d final M1]`.
- Programski odabir na GTK-u možda ponovno širi čvor koji je korisnik skupio (neprovjereno): `StructureTree`
  `[6d final M3]`.
- Strane koje nisu parsirane čuvaju se prazne cijelu sesiju nakon prolazne greške: `StructureController.build`/`safe`
  `[6d final M4]`.
- Pozadinski poslovi se ne otkazuju: `StructureController.compute` `[6d final M5]`.
- Outline stranica ostaje vezana uz uništen viewer kad editor ponovno stvori viewer: `StructureOutlinePage.attach`
  `[6d final M6, T6 M2]`; testovi ne pinaju „ostali editori nepromijenjeni“ `[6d T6 M3]`.
- Kvačica „Show all“ ne prati preferenciju u drugim otvorenim resolverima: `StructurePanel` `[6d final M9, T5 M2]`.
- Prazan spremnik tretiran kao list `[6d T1 #5]`; `ManifestStructure` aritmetika redaka slabo testirana,
  zaglavlje liste s prvom klauzulom, obrnuti rasponi `[6d T2 M1, M3, M4]`; M3/M4 Taska 3 (provjeriti).
- Smještaj čipa i odabrani redak u owner-draw, redoslijed `fire()`/`update()`, rupe u testovima, logiranje
  greške ikone jednom po stablu `[6d T4 M6–M9]`; minimalne širine panela, tipka s fokusom u stablu, duljina
  redaka u `plugin.xml` `[6d T5 M3–M5]`; razlika u kapi širine od nekoliko piksela.

### Postavke
- Okviri su globalni, sheme po temi: `AppearancePreferencePage` `[prefs final M13]`.
- `ProjectSettings.enabled` logira WARNING pri svakom upitu dok su postavke projekta nečitljive; jednom po
  imenu projekta `[prefs final M14, T1 M5]`.
- Veza „Configure Workspace Settings“ nakon OK, zatvoren projekt na stranici projekta, bilješka da otvoreni
  editori primjenjuju promjene tek pri sljedećem otvaranju, rupe u testovima (Apply, Cancel):
  `AfterGitPropertyPage` `[prefs T5 M1–M4]`.
- Migracija boja: `checked` se postavlja prije `run()`, `MIGRATED` briše `reset()`, nema testa tamnog prvog
  pokretanja, `ThemeSwitch.settle` pada tiho nakon 5 s `[prefs T7 N1–N5]`.
- Akcije koje mijenjaju broj redaka (organize imports) i dalje diraju više redaka: `JavaPanes.saveWithActions`
  `[prefs T6 N1]`; javni testni hook `JavaPanes.setSaveFailureForTests` (prihvaćen obrazac) `[prefs T6 N2]`;
  bez zaštite za `getDocument()` null `[prefs T6 M2]`.
- Tooltip ključeva se ne osvježava bez testa, `AiActions.resolveConflictCommand` nije pokriven, podizbornici po
  resolveru bez tipki, obrađivači uvijek omogućeni `[prefs T8 M1–M4]`.

### MCP
- `BlockId.find` uzima prvi pogodak; oblici parsiranja `[6a T1]`.
- `ProposalStore`: pozivatelji moraju paziti dok je AI isključen, `deleteAll` gubi memoriju / utrka, `put` iznad
  `MAX_TEXT`, čišćenje `.tmp` `[6a T4]`.
- Učitavanje prijedloga s IOException prekida popis, kodiranje otvorene datoteke s diska, ponovno čitanje merge-basea
  bez ograničenja `[6a T6]`.
- Završno uklanjanje revertom po bloku a ne po izvoru, greška uklanjanja nakon withdraw, greška zatvaranja editora
  `[6a T8 N1–N3]`.
- `url` −1 tijekom stopa, ponovni ulazak upita za tajnu kad dopusti prompt, tekst upozorenja `[6a T9 N1–N3]`.
- Četiri zaustavljena djelomična zahtjeva mogu odgoditi ostale (samo loopback, dokumentirano) `[6a T2]`.
- `AgentServer`: server se ne zaustavlja ako `registerService` baci nakon `start()`; nakon neuspjela
  `tokens.create()` izostaju `fire()` i `showStatus()` `[6a final M4, M5]`.
- `StoreProposalSource.stored` hashira cijelu radnu datoteku i čita indeks na UI niti bez ograničenja
  `[6a final M8]`.
- Korijenska stranica `net.aftergit.merge.preferences` živi u opcionalnom mcp bundleu; kasniji resolver mora
  koristiti isti id iz `merge.ui` `[6a final M11]`.
- Riječ „PENDING“ u poruci, test modalnog dijaloga, Apply tijekom pitanja o spremanju `[6a T5 N1–N3]` (provjeriti).

### AI
- Tvrdo kodirano „200 000“ u poruci (`AiRequests.checkSize`), nema testa praznog retka `[6b T1]`.
- `AiResolverContributions` stvara klasu prije provjere id-a, jedna poruka za tri uzroka; test ne provjerava
  porijeklo korijenske stranice; INSTALL kaže da „Preferences > AfterGit“ sada uvijek postoji `[6b T2]`.
- `aiApplied` je izvan undo/redo, withdraw agenta može vratiti AI odgovor istog teksta, ime resolvera „auto“,
  rupe u testovima: `MergeViewer` `[6b T3 M1–M4, final M8]`.
- `AiActions.filePath()` čita ulaz viewera izvan UI niti, NOT_FOUND skrivenih resolvera javlja „conflict is gone“,
  `AiException` bez poruke daje „null“, nema testa izbornika u Java panelu `[6b T4 M1, M3–M5]`.
- `AiActions.onUi` guta `SWTException`, „1 waiting“ za zadržan odgovor koji se ipak primijeni `[6b T5 M2, M4]`.
- Parser odgovora: jedan marker, graničnik, host s podcrtom, ugniježđeni start, prvi loš zaustavlja
  `[6b T6 M-1, M-2b, M-5, M-A, M-B]`.
- Uspjeh je samo status 200 i string sadržaj; zadani timeout naspram `max_tokens` `[6b T7 M6, M7]`.
- Pohranjeni ključ čita se na UI niti pri Test Connection, nema Cancel gumba, `PageValuesTest` bez `saved()`
  `[6b T8 M2–M4]`.
- Čekajući AI odgovori ostaju vidljivi nakon isključivanja dok se ne otvori izbornik: `AiActions.setAiActive`
  `[6b final M4]`.
- Duljina ograde u promptu je neograničena (do ~2x ograničenja), SSL rukovanje se bilježi kao „sent“, `warned`
  stanje stranice, dijalog privole bez napomene o `http` `[6b final-rereview N1–N4]`.

### Languages Dio 1
- Oblik `isJava` / `forFileName` i nevolatilni `wired` u `LspBridge` `[lsp1 T1 M1, M3]` (M1 premješten u Task 2, provjeriti).
- Predmemorija tokena `PaneTokens` po identitetu `IToken`; promjena teme izvan UI niti baca `SWTException`; null tema
  daje poplavu grešaka; oracle uspoređuje samo prvi znak tokena `[lsp1 T2 M3–M6]`.
- Gramatike koje ugrađuju Markdown (F#) ipak ga učitavaju; `isMarkdown` isključuje datoteku ako je ijedan njezin
  content type Markdown; test crte preko boja ne provjerava boju izvan bloka; strane se renderiraju dvaput radi
  duljine `[lsp1 T3 M2, M3, M6]`.
- Slušač postavke počinje tek u prvom `createViewer`; osobne postavke samo za INNER_LINE i LAYOUT u testu
  `[lsp1 T4 M1, M2]`.
- Test `LanguageThemesTest`: provjera boje sukoba skenira cijelu kontrolu s tolerancijom 8, zastarjeli komentar,
  zasjenjena varijabla `dark` `[lsp1 T6]` (provjeriti, bilo je upućeno Tasku 7).
- Preostali dio prethodne točke: tm4e greška nakon izgradnje reconcilera i dalje ruši otvaranje (vidi §3)
  `[lsp1 final-rereview M2]`.

### Languages Dio 2
- Test bez red-koraka: „no pile-up“ test nije pokrenut protiv starog koda, samo obrazložen `[lsp2 re-review 3]`.
- Regresijski test za `errors()` pri cancelu/iznimci `[lsp2 T4]` (provjeriti, bio je upućen Tasku 7).
- Particioner kao korisnik zajedničkog TM modela, ponovljeni tm4e WARNING `[lsp2 T3 D, E]` (D je ostao kao
  pretpostavka, vidi §3).
- Filtar `SideClient.registers` po imenu metode (watch, register, subscribe, listen) `[lsp2 re-review 3]`.

### Testovi
- Vremenski margina testa velike strane (900 000 znakova, <500 ms) nije zapisana, globalni hook „zadnji poziv“
  `[semantic M-B]`; živi prekid teme i drugi uzorak za usporedbu riječ po riječ `[semantic M2, M6]`.
- Neodređeni redoslijedi: GTK crta na vlastitom frame clocku (uzrok ranijih nestabilnih testova); suite koristi
  WWD i LemMinX iz targeta (`ui-tests` profil).
- Rupe: undo Take-a nad AI blokom, Take desno s konektora, `nextAnyConflict(m, -1, …)`, hod gumbima kroz blok od
  0 redaka, preimenovanje `AutoOverrideTest.toolbarAndKeysTakeAPendingAiResolution` `[states final M9]`.
- Tri `@Disabled` testa u `merge.core` (duplikat ključa) `[6c T8]`.
- `NoEGitUiTest` ne hvata ponovni izvoz kroz `merge.git`; `onlyOneConflictedTextFileGoesToAfterGit` slabije
  provjerava nego što docs kaže `[staging M6, rereview2]` (provjeriti).

### Dokumentacija
- INSTALL: prijelom retka usred rečenice u točki o članovima `[6c final N2]`.
- INSTALL: redak alatne trake znatno duži od širine datoteke `[states final M11]` (provjeriti).
- `visual-checklist.md` upućuje na snimke koje ne prikazuju značajku (redovi 24, 25 u ranijoj numeraciji) i mijenja
  zaslon snimanja `[states final M1]` (provjeriti).
- INSTALL: „Preferences > AfterGit“ sada postoji već uz sam resolver `[6b T2 M3]`.
- Dizajn §13 i dalje navodi tri otvorena pitanja bez statusa (vidi §2).
