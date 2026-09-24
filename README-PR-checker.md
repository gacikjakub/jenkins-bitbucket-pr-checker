# Jenkins PR review orchestrator — POC

Wersja dla wielu repozytoriów i zespołów jednej instancji Bitbucket Data Center. Wynikiem jest globalny plan review i podglądy maili. Pipeline zapisuje przydziały w komentarzach z markerem. Nie zmienia natywnej listy reviewerów; wysyłka maili jest domyślnie wyłączona (notifications.previewOnly: true). Nie integruje urlopów ani Power Automate.

## Instalacja

Umieść w repozytorium joba Jenkins:

```text
Jenkinsfile-PR-checker.groovy
pr-checker.yaml
pr-checker/
  Orchestrator.groovy
  email-template.html
```

Job: **Pipeline script from SCM**, Script Path: `Jenkinsfile-PR-checker.groovy`. Pipeline wykonuje `checkout scm`; YAML i moduł są czytane względem katalogu głównego workspace. Wymagane: agent Unix/Linux z curl i dostępem do Bitbucketa, Pipeline, Credentials Binding, Pipeline Utility Steps (`readYaml`, `readJSON`, `writeJSON`, `readProperties`), Timestamper. Ustaw etykietę agenta zamiast `agent any`, jeśli Jenkins ma również agenty Windows.

Uzupełnij przykładowy YAML. Credentials username/password pozostają w Jenkins; YAML zawiera tylko `credentialsId`, wiązany w każdej gałęzi jako `GIT_USER`/`GIT_PASS`. Harmonogram pozostaje `H * * * *` w strefie schedulera Jenkins. Limit całego uruchomienia wynosi 60 minut; przy dużej liczbie repozytoriów można go dostosować. Katalog generowanych plików `pr-checker-output` jest czyszczony na początku uruchomienia i należy go zarezerwować dla tego joba.

## Konfiguracja i migracja

`schemaVersion: 2` zastępuje poprzednie `repo` i `teamMembers` strukturą `bitbucket`, `teams` oraz `repositories`. Stara konfiguracja jest odrzucana z komunikatem, aby nie uruchomić przypadkiem niepełnego planu.

- `teams[].members`: jedyne źródło przynależności osoby do zespołu. Lista reviewerów Bitbucketa nigdy nie tworzy zespołu. Liczba osób i zespołów jest dowolna, także zespół pusty. Jedna osoba należy w tej wersji do jednego zespołu; LAN ID i email muszą być globalnie unikalne bez rozróżniania wielkości liter.
- `repositories[]`: jawna lista repozytoriów objętych tym samym globalnym bilansem. Każde ma unikalne `id`, `projectKey`, `slug` oraz niepustą listę `codeownerTeams`. Nie ma automatycznego odkrywania repozytoriów ani czytania plików CODEOWNERS z repozytoriów.
- `codeownerTeams: ['team-a', 'team-b']`: oba zespoły są właścicielami repozytorium. Przy wyborze przedstawiciela właścicieli kandydaci pochodzą z sumy ich członków; nie jest wymagany osobny approval od każdego zespołu właścicielskiego.
- `lookbackDays: 14`, `scoring.baseReviewPoints: 50`, `mergedPageSize: 30`: zasady historii i punktacji.
- `policy.largePrThreshold: 300`: duży PR ma **co najmniej 300 dodanych + usuniętych linii**. Rozmiar użyty do podziału na mały/duży nie zmienia wzoru punktacji.

Project key i slug podaj w postaci właściwej dla URL. Repozytoria mogą mieć tych samych członków wśród codeownerów przez wskazanie tego samego zespołu. Zduplikowane repozytorium, nieznany codeowner, niejednoznaczna tożsamość albo nieprawidłowe liczby zatrzymują job.

## Etapy i transfer danych

1. **Load configuration** — checkout, odczyt i walidacja YAML, wspólny początek okna czasowego.
2. **Collect repositories in parallel** — osobna gałąź dla każdego repo. Pobiera OPEN bez draftów, MERGED w oknie, statystyki diffów, approvals, autorów, buildy i mergeability dla OPEN. Liczy koszt review poszczególnych PR-ów. Zapisuje własny `repos/<id>/snapshot.json`.
3. **Aggregate and plan globally** — dopiero po poprawnym zakończeniu wszystkich gałęzi. Łączy dane, sumuje historię każdej osoby ze wszystkich repozytoriów, rezerwuje koszt już wykonanych review OPEN, a następnie sekwencyjnie planuje nowe review.
4. **Render email previews** — tworzy `index.html`, plik HTML per osoba, `email-previews.json` (adresat/temat/plik) i archiwizuje artefakty w Jenkins.

Model jest obiektowy w sensie rekordów domenowych, ale implementowany przez zwykłe serializowalne mapy i listy, żeby nie dodawać problemów z klasami, classloaderami, CPS i Sandbox. Moduł nie ma współdzielonego mutowalnego stanu. JSON jest granicą między równoległymi collectorami a plannerem; błędy jednego repo zatrzymują planowanie całego zestawu. Nie ma liczenia punktów w mapie współdzielonej przez gałęzie parallel.

| Rekord | Najważniejsze dane |
| --- | --- |
| RepositorySnapshot | `schemaVersion`, `repositoryId`, `collectedAt`, `openPullRequests`, `mergedPullRequests` |
| PullRequest | `key` jako repo#id, autor i jego team, `codeownerTeams`, tytuł/status, commity, diff, koszt review, approvals, assignment, build, merge |
| ReviewPlan | tryb PREVIEW_ONLY, globalne `scores`, członkowie, PR-y, `assignments` |
| AssignmentProposal | PR, dozwolone zespoły dla każdego miejsca, istniejące zobowiązania, proponowane osoby, status i powód |

## Polityka przydziału

| Autor | Mały PR | Duży PR |
| --- | --- | --- |
| Należy do któregokolwiek zespołu codeownerów | 1 osoba z zespołu autora | 2 różne osoby z zespołu autora |
| Należy do innego skonfigurowanego zespołu | 1 osoba z zespołu autora | 1 z zespołu autora + 1 spośród wszystkich zespołów codeownerów |
| Brak autora w konfiguracji | MANUAL | MANUAL |

Liczby dla autora wewnętrznego są konfigurowane przez `ownTeamSmallReviewers` i `ownTeamLargeReviewers`. Autor PR-a jest zawsze wykluczony. Istniejący approval właściwej osoby może już spełniać wymagane miejsce. Jedna osoba nie wypełni dwóch miejsc. Brak wystarczającej liczby kandydatów daje MANUAL z powodem; nie zatwierdzamy częściowego planu ani nie rezerwujemy częściowych punktów.

PR-y są planowane od najstarszego `createdDate`, z remisem po kluczu repo#id. Wśród kandydatów wybieramy najmniejszy effectiveScore, przy remisie LAN ID. Wybrana osoba otrzymuje rezerwację kosztu przed przejściem do kolejnego PR-a. Kolejność zakończenia gałęzi parallel nie zmienia kolejności planowania. Kod nie używa sortowania z komparatorem Groovy, które wcześniej powodowało problemy CPS.

## Punkty

```text
sizePoints = max(points z pasujących scoring.sizeRules), domyślnie 0
reviewPoints = scoring.baseReviewPoints + sizePoints
awardedPoints = reviewPoints + fastReviewBonus
effectiveScore = historical score + openPoints + proposedPoints
```

Historical score liczy pełny koszt dla każdego rzeczywiście zatwierdzającego reviewera z konfiguracji, raz na PR zmergowany w ostatnich lookbackDays. Autor może pochodzić spoza konfiguracji. Źródłem uprawnienia do punktów pozostaje bieżący approval z `reviewers[]` (status APPROVED lub approved=true). Historię APPROVED odczytujemy do ustalenia czasu premii; sam dawny approval z historii nie daje punktów. Okno określa `closedDate`, a nie moment kliknięcia approve.

`openPoints` rezerwuje koszt review już rozpoczętych na OPEN: approval, CHANGES_REQUESTED albo obecność `lastReviewedCommit` (również po resecie decyzji). Po przyszłym podłączeniu czytnika markerów uwzględni też znane trwałe przydziały. Każda osoba/PR jest liczona raz. Rezerwacje z wszystkich repo powstają przed pierwszym nowym wyborem. `proposedPoints` to koszt propozycji tego przebiegu. Sam wpis w natywnej liście reviewerów bez decyzji/lastReviewedCommit nie jest traktowany jako zlecenie pracy. Raport oddziela historyczne score/reviews/sizePoints od rezerwacji. CHANGES_REQUESTED nie daje historycznych punktów za approval.

MERGED jest pobierane stronami po 30 z `order=CLOSED_DATE` do przekroczenia granicy lookback, bez limitu łącznego; data dokładnie na granicy jest uwzględniona. Nieprawidłowa paginacja lub kolejność zatrzymuje job. API nie oferuje tu atomowego snapshotu całej instancji. Dla OPEN ponownie sprawdzamy stan, wersję i commity po odczytach; zmiana PR-a zatrzymuje kolekcję i wymaga powtórzenia joba. Przed przyszłym zapisem przydziału konieczna będzie ponowna weryfikacja bieżącego stanu.

## Buildy i konflikty

Buildy pobierane są dla **aktualnego source commita i repozytorium źródłowego**, także dla forka, ze stronicowanego `.../commits/{commit}/builds`. Agregacja wszystkich opublikowanych kluczy buildów: FAILED ma pierwszeństwo, dalej CANCELLED, INPROGRESS, UNKNOWN, SUCCESSFUL. Dla powtórzonego klucza wybierany jest najnowszy znacznik `updatedDate`/`dateAdded`. Nie definiujemy jeszcze listy wymaganych jobów CI.

`NO_STATUS` oznacza brak opublikowanego statusu; nie jest dowodem, że build nie został striggerowany. `observed` mówi tylko, czy API zwróciło status. Błąd uprawnień, endpointu lub danych daje UNKNOWN. Buildy nigdy nie są uruchamiane przez checker.

Mergeability pochodzi z `GET .../pull-requests/{id}/merge`. Konflikty bierzemy wyłącznie z `conflicted`, zdolność do merge'a z `canMerge`; brak pola daje UNKNOWN. Vetoes/merge checks są wypisywane osobno. `canMerge=false` nie jest utożsamiane z konfliktem. Błąd opcjonalnego odczytu nie zeruje historii review, tylko oznacza status UNKNOWN. Przerwanie joba i timeout nie są połykane. Build/merge status na tym etapie informuje w raporcie, a nie blokuje planowania review.

## Komentarze i maile — punkty rozszerzenia

### Stan review osoby i ponowne review

Każdy OPEN ma `reviewActivity.byMember[lanId]`: bieżący `status` (APPROVED, CHANGES_REQUESTED, PENDING, UNKNOWN), `lastReviewedCommit`, daty approvala/prośby o zmiany/komentarza, porównanie rewizji i `needsAnotherLook` (true/false/null). Bitbucketowy NEEDS_WORK jest normalizowany do CHANGES_REQUESTED. Ta osoba pozostaje zaangażowana i widzi PR w swojej sekcji; planner nie zastępuje jej innym reviewerem tylko dlatego, że poprosiła o zmiany. COVERED oznacza wykonane approvals, a REVIEW_IN_PROGRESS — obsadzone review wymagające dalszej pracy. Żaden z tych statusów planu nie potwierdza spełnienia serwerowych merge checks.

Collector czyta wszystkie strony `/activities` dla OPEN i pobranych MERGED, bez obcinania aktywności do okna lookback. Szuka ostatniej aktywności danej osoby: APPROVED, REVIEWED/NEEDS_WORK jeśli występuje oraz opublikowanych komentarzy, edycji i odpowiedzi. Usunięcie komentarza nie jest nowym feedbackiem. Osobny odczyt activities rozpoznaje markery przydziału konta bota. Daty są datami zdarzeń serwera; nie używamy authorTimestamp ani committerTimestamp jako dat pushowania.

Aktualizacja source gałęzi jest wykrywana po RESCOPED z różnymi `previousFromHash` i `fromHash`, pasującym do aktualnego source commita. Zmiana samego targeta nie wywołuje alertu. Aktualizacja po feedbacku jest sygnalizowana w mailu jako „New changes since feedback”; JSON dodatkowo rozróżnia potwierdzone dodanie commitów przez `added.total > 0`. To czas zaobserwowania zmiany przez PR, nie gwarantowany dokładny czas pushowania.

`lastReviewedCommit` różny od bieżącego source commita pozwala wykryć zmianę nawet bez daty decyzji. Komentarz po ostatniej znanej aktualizacji wycisza sugestię ponownego spojrzenia do następnej zmiany. Zgodna rewizja dla aktualnej decyzji oznacza brak nowszej rewizji do review. Brak dostatecznych danych daje UNKNOWN, a niedostępność `/activities` nie usuwa znanego statusu osoby.

**Ograniczenie API:** NEEDS_WORK nie zawsze ma odpowiadający wpis w activities. Wówczas `changesRequestedAt` pozostaje UNKNOWN, ale stan i porównanie rewizji nadal działają. Dokładnej daty nie zastępujemy datą dowolnego komentarza. Jeśli API resetuje status i nie zachowuje lastReviewedCommit ani zdarzenia decyzji, bez przyszłego trwałego stanu/webhooka nie odtworzymy poprzedniej decyzji. Alert jest obliczany w każdym przebiegu; nie ma jeszcze trwałego mechanizmu „powiadom tylko raz”.

Kompaktowy mail prezentuje status każdego wybranego/zaangażowanego reviewera i informację o zmianach po feedbacku. Szczegółowe timestampy pozostają w JSON. Po wcześniejszym approve również może pojawić się sugestia ponownego review, jeżeli źródło się zmieniło. Zwykły komentarz sam w sobie nie jest approvalem ani automatycznym przydziałem.

## Premia za szybkie review

YAML `scoring.fastReview` zawiera `bonusPoints: 10`, `smallHours: 3`, `largeHours: 6`. Mały PR ma mniej niż `policy.largePrThreshold` zmienionych linii; przy 300 liniach obowiązuje już 6h. Granica czasu jest włącznie.

Collector dokleja `reviewActivity.actions` z akcją, timestampem serwera, tożsamością osoby i metadanymi rewizji/draft. W etapie globalnym powstają `reviewAwards` per osoba/PR. Początkiem jest późniejsza z ostatniej aktualizacji source do obecnej rewizji (RESCOPED) i przejścia do ready (`UPDATED` z jawnym `draft:false`). Zwykłe UPDATED bez pola draft nie zeruje zegara. Końcem jest ostatnie APPROVED tej osoby, bez późniejszego wycofania decyzji. Nie naliczamy premii dla starej rewizji ani approvala wcześniejszego niż start.

Nie podstawiamy daty utworzenia PR-a ani daty autora commita. Gdy brak udokumentowanego momentu push/ready lub approvala, premia wynosi zero (`TIMING_UNKNOWN`), a zwykłe punkty pozostają. Dotyczy to m.in. PR-a utworzonego od razu jako non-draft bez późniejszego RESCOPED/ready: historia PR-a może nie znać czasu pusha sprzed utworzenia. RESCOPED podaje czas zaobserwowania zmiany przez Bitbucket, a nie niezależny rejestr pushy. Pola zdarzenia przejścia draft należy potwierdzić na Waszej wersji REST; nierozpoznane zdarzenie nie jest zgadywane.

Premia zwiększa historyczny `score`, a więc **odciąża osobę**, bo planner wybiera najmniejszy `effectiveScore`. Jest też osobno w `bonusPoints`. Dla aktualnych approvali OPEN premia zwiększa rezerwację `openPoints`; nie jest przyznawana z góry osobom dopiero proponowanym. Jedna osoba dostaje najwyżej jedną premię na PR. Nie ma specjalnej logiki ani ostrzeżeń dla approvali po merge.

## Kompaktowe maile i wersje

Szablon `pr-checker/email-template.html` ma style inline; zawartość PR-ów jest escapowana, bez wykonywania kodu szablonu. OPEN trafia do pierwszej pasującej sekcji:

1. **PRs created by you** — autor jest adresatem; reviewerzy obejmują również osoby nieprzypisane i spoza konfiguracji, jeśli są w danych reviewerów/participants API.
2. **PRs assigned to you** — adresat jest wybrany lub już zaangażowany; wyświetlany jest jego status, a nowa propozycja ma etykietę „Proposed assignment”.
3. **PRs of team members** — przydział do innych osób z zespołu; jeden wiersz na PR, również przy kilku reviewerach.
4. **Other PRs in team repositories** — pozostałe PR-y w repozytoriach, których codeownerem jest zespół adresata.
5. **Recently merged** — wszystkie pobrane MERGED ze wszystkich skonfigurowanych repozytoriów w oknie lookback, bez limitu pięciu pozycji. Grupy po repozytorium i wersji; każdy PR ma własny tytuł/link i autora.

Pierwsze trzy tabele zawierają Build oraz **Diff `+added/-deleted` bezpośrednio przed reviewerami**. Statusy w mailu: APPROVED, NEEDS_WORK, UNAPPROVED (lub UNKNOWN). Age nadal oznacza wiek PR-a od utworzenia; nie jest zegarem premii. Potwierdzone konflikty są krótką informacją pod buildem. W przypadku braku displayName w odpowiedzi API używane jest opcjonalne `teams[].members[].displayName`, a ostatecznie LAN ID.

Dla każdego MERGED parallel collector czyta `raw/gradle.properties?at=<sourceCommit>` z **repozytorium źródłowego**, również dla forka. `readProperties(interpolate:false)` odczytuje klucz `version` z roota. Nie korzystamy z najnowszej wersji target brancha ani późniejszego commita pipeline'u. Brak pliku/uprawnień/klucza daje UNKNOWN w mailu i odpowiedni `projectVersion.status` w JSON; nie blokuje scoringu. Wspólna wersja w dwóch repo nie łączy ich w jedną grupę.

## Testy i ograniczenia

Testy lokalne obejmują wiele zespołów/codeownerów, próg 299/300, globalne sumy z różnych repo z takim samym numerem PR, rezerwacje przed przydziałem, remisy, brak kandydatów, autora spoza konfiguracji, wpływ kolejności kolekcji, paginację i granicę 14 dni, źródłowy fork dla buildów, UNKNOWN i propagację przerwania, zmianę wersji PR-a, JSON round-trip, HTML escaping i osobne powiązania gałęzi parallel. Dodatkowo: granice 3h/6h, ready po pushu, reset decyzji, stara rewizja, brak historii, wpływ premii na score, wersja z forka, brak pliku/klucza, grupowanie wersji i rozłączność sekcji.

Uruchomienie testów z katalogu repo wymaga Groovy 3 oraz SnakeYAML 2 na classpath, np. `groovy -cp /path/to/snakeyaml-2.0.jar tests/verify-orchestrator.groovy`. Dołączona atrapa FlowInterruptedException służy tylko testom bez Jenkinsa. Testy nie wykonują Declarative DSL ani transformacji CPS/Sandbox. Integracja wymaga pierwszego przebiegu na Waszym Jenkinsie i Bitbuckecie.

Nazwy pól diff stats pozostają adapterem z poprzedniej wersji: `addedLines` / `linesAdded` / `totalLinesAdded` i odpowiedniki usuniętych linii. Brak rozpoznanego pola zatrzymuje job, zamiast naliczać błędne zero. Format trzeba potwierdzić na Waszej instancji.

## Źródła

- [Bitbucket 9.4 — Pull Requests REST API](https://developer.atlassian.com/server/bitbucket/rest/v904/api-group-pull-requests/)
- [Bitbucket — endpoint buildów przypisanych do commita](https://developer.atlassian.com/server/bitbucket/rest/v803/api-group-api/)
- [Bitbucket 9.4 — kolejność CLOSED_DATE](https://docs.atlassian.com/bitbucket-server/javadoc/9.4.0/api/com/atlassian/bitbucket/pull/PullRequestOrder.html)
- [Jenkins — Pipeline Utility Steps](https://www.jenkins.io/doc/pipeline/steps/pipeline-utility-steps/)
- [Bitbucket — status NEEDS_WORK i lastReviewedCommit](https://developer.atlassian.com/server/bitbucket/rest/v900/api-group-pull-requests/)
- [Bitbucket — przykłady activities, RESCOPED i komentarzy](https://docs.atlassian.com/bitbucket-server/rest/7.4.0/bitbucket-rest.html)
- [Atlassian — rescoping po pushu](https://support.atlassian.com/bitbucket-data-center/kb/how-to-diagnose-debug-issues-with-pull-request-rescoping-in-bitbucket-data-center/)
- [Atlassian — REVIEWED może nie tworzyć activity](https://docs.atlassian.com/bitbucket-server/javadoc/5.5.8/api/reference/com/atlassian/bitbucket/pull/PullRequestAction.html)

- [Bitbucket — wprowadzenie zdarzenia zmiany draft w 8.18](https://developer.atlassian.com/server/bitbucket/reference/api-changelog/)

## Powiadomienia bez powtarzania maili

Harmonogram jest godzinowy. `notifications.previewOnly: true` generuje podglądy i decyzje (`send`, `reasons`, `newKeys`) bez wysyłki ani potwierdzania stanu. Aby włączyć wysyłkę, ustaw false i skonfiguruj Email Extension/SMTP w Jenkinsie.

`notifications.stateDirectory` musi wskazywać istniejący lub możliwy do utworzenia, zapisywalny, **trwały i osobny dla tego joba katalog na agencie Unix**, poza checkoutem. Jeśli agenty się zmieniają, zamontuj ten sam wolumen. Nie czyść go między buildami. Brak stanu oznacza pierwsze uruchomienie: wszystkie bieżące przydziały są NEW, a statusy autorskich PR-ów tworzą punkt odniesienia. Uszkodzony stan zatrzymuje przebieg. `disableConcurrentBuilds` chroni przed równoległymi uruchomieniami tego joba; nie współdziel katalogu z innymi jobami.

Mail jest wysyłany tylko przy nowym przydziale do adresata, zmianie zestawu statusów reviewerów na jego autorskim PR-ze lub przekroczeniu 4/8/12 godzin przez najstarszy PR czekający na jego review. Każdy próg wysyłany jest raz dla pary PR/początek cyklu; przy pominięciu kilku progów powstaje jeden mail dla najwyższego. Po 12h nie ma dalszych cyklicznych przypomnień. Approval i prośba o zmiany kończą oczekiwanie na review, chyba że wykryto nowe zmiany po feedbacku. Czas jest liczony od source update/ready; brak timestampu nie jest zastępowany datą utworzenia. Zmiana samego buildu i samo pojawienie się merged PR-a nie uruchamia maila.

Stan zapisujemy osobno dla każdej osoby dopiero po poprawnym powrocie kroku emailext; nieudana wysyłka może być ponowiona. SMTP nie zapewnia dokładnie-jednorazowego dostarczenia: awaria po przyjęciu maila, lecz przed zapisem stanu może spowodować duplikat. Tryb podglądu nie zużywa powiadomień, dlatego przy braku trwałego stanu NEW jest widoczne w każdym podglądzie. Przydziały są zapisywane w komentarzach przed etapem maili. Nowy przebieg odczytuje je i zachowuje jako existing commitments.

[NEW] pojawia się pogrubione przed linkiem nowo przydzielonego PR-a. Build: zielony check SUCCESSFUL, czerwony X FAILED/CANCELLED, klepsydra INPROGRESS, szara kreska UNKNOWN/NO_STATUS. Żółty trójkąt konfliktu znajduje się przy buildzie tylko w tabelach autorskich i przypisanych PR-ów. Age w każdej tabeli OPEN jest żółte po przekroczeniu 4h, czerwone po przekroczeniu 8h; oznacza czas od utworzenia PR-a, niezależnie od zegara przypomnień.

## Filtrowanie target branchy

Globalnie YAML zawiera `targetBranchPatterns: ['develop', 'release-candidate']`. Każde repo może mieć własne `targetBranchPatterns`, np. `['develop', 'release/*']`. Nadpisanie **zastępuje** całą listę globalną; brak klucza dziedziczy ją, a pusta lista pomija wszystkie PR-y danego repo.

Wzorce są globami, nie regexami: `*` oznacza dowolny ciąg (także `/`), `?` jeden znak, pozostałe znaki są dosłowne. Dopasowanie obejmuje całą krótką nazwę brancha i rozróżnia wielkość liter. Podawaj `develop`, nie `refs/heads/develop`. Sprawdzamy wyłącznie target (`toRef`), nie source. Brak rozpoznanej nazwy targetu oznacza pominięcie PR-a.

Filtr działa dla OPEN i MERGED **przed** odczytem diffów, aktywności, buildów czy wersji. Pominięte PR-y nie trafiają do snapshotów, więc nie wpływają na przydziały, scoring, raporty ani powiadomienia. Paginacja MERGED nadal kończy się na granicy lookback według wszystkich zwróconych closedDate — strona bez pasujących branchy nie kończy pobierania. Zmiana konfiguracji może usunąć przydział ze stanu powiadomień; przy ponownym objęciu PR-a filtrem będzie traktowany jako NEW.

## Konfigurowalne reguły scoringu

Wspólna sekcja `scoring` zawiera `baseReviewPoints`, `fastReview` (bonusPoints, smallHours, largeHours) i `sizeRules`. Dotychczasowe top-level baseReviewPoints/fastReview trzeba przenieść — walidacja odrzuca pozostawienie starych pól. Dawna formuła abs(added-deleted) została zastąpiona tabelą reguł.

Każda reguła ma unikalne `id`, `metric` (`changedLines` = added+deleted lub `changedFiles`), `min`, opcjonalne `max` oraz `points`. Granice są włączne; brak max oznacza brak górnego limitu. Przedziały mogą się nakładać, także między metrykami. Wybierana jest tylko jedna reguła z największą punktacją. Przy remisie wygrywa pierwsza w YAML; wynik punktowy pozostaje taki sam. Brak dopasowania lub pusta lista daje zero punktów za rozmiar. Ujemne wartości, błędna metryka, odwrócony przedział lub duplikat id zatrzymują walidację.

Przykładowy YAML przyznaje 10 pkt za 0–29 linii, 30 pkt za 30–299, 60 pkt za co najmniej 300 oraz 80 pkt za co najmniej 10 plików. Są to przykładowe wartości do dostosowania. Dla 350 linii i 12 plików wynik wynosi 50 bazowych + 80 za rozmiar + ewentualne 10 za szybkość, czyli 130 lub 140. Wyższe punkty odciążają osobę przy kolejnych przydziałach.

DTO przechowuje `diff.changedFiles`, `diff.sizeRule` i `diff.sizePoints`, więc można sprawdzić wybraną regułę. Liczba plików jest wymagana tylko przy regułach changedFiles; odczytujemy ją z diff-stats-summary przez adapter nazw modifiedFiles/filesModified/filesChanged/changedFiles/totalFilesChanged. Brak rozpoznanej wartości zatrzymuje kolekcję, zamiast liczyć zaniżone punkty. Format należy potwierdzić na Waszym Bitbuckecie. Próg policy.largePrThreshold nadal niezależnie steruje przydziałami i czasem premii 3h/6h. Wszystkie MERGED w lookback są przeliczane według bieżącej konfiguracji scoringu.

## Trwałe przydziały w komentarzach

Tożsamość bota pochodzi bezpośrednio z `env.GIT_USER` ustawionego przez `withCredentials`, zarówno w gałęziach parallel, jak i przy zapisie. YAML zawiera tylko `bitbucket.credentialsId`; nie ma osobnego botUsername. Username w credentials powinien być loginem Bitbucketa (nie displayName ani email), porównywanym z `comment.author.name` bez rozróżniania wielkości liter. Brak GIT_USER zatrzymuje działanie zamiast uznawać brak markerów. Konto potrzebuje praw do czytania PR-ów oraz tworzenia/edycji własnych komentarzy. Zapis komentarzy działa niezależnie od `notifications.previewOnly`, które kontroluje wyłącznie wysyłkę maili.

Parallel collector czyta wszystkie strony activities, szuka komentarza zaczynającego się markerem i sprawdza jego autora. Następnie pobiera aktualną treść komentarza po ID, aby nie interpretować historycznej wersji z activities. Do `PR.assignment` trafia status KNOWN, lista kanonicznych LAN ID reviewerów, commentId i commentVersion. Brak markera po poprawnym odczycie daje NONE. Błąd API daje UNKNOWN, błędna zawartość INVALID, a kilka markerów AMBIGUOUS; takie PR-y wymagają ręcznej interwencji i nie są automatycznie zapisywane. Komentarze innych autorów nie są zaufanym źródłem przydziałów.

Format komentarza:

```text
[jenkins-pr-review:v1]
{"schemaVersion":1,"reviewers":["lan002","lan003"]}

Assigned reviewers: Jan Nowak, Maria Lis
```

Po globalnym planowaniu osobny sekwencyjny stage zapisuje przydziały. Pierwszy zapis to POST do comments; uzupełnienie listy to PUT istniejącego komentarza z jego wersją. Jeśli lista jest niezmieniona, nie ma zapisu. Dotychczasowi zaangażowani reviewerzy są zachowani. Przed zmianą następuje ponowny odczyt markera i kontrola stanu OPEN/non-draft, wersji PR-a, rewizji source/target i brancha. Zmiana danych zatrzymuje przebieg. Po zapisie następuje odczyt weryfikacyjny; dopiero potem uruchamiane są maile. Nie ma automatycznego ponawiania POST po niepewnym wyniku — kolejny build najpierw ponownie szuka markera, ograniczając ryzyko duplikatu.

`disableConcurrentBuilds()` bez abortPrevious jest już w deklaracji pipeline: kolejne buildy tego samego joba czekają w kolejce. Nie jest to blokada innych jobów ani instancji Jenkins — jeden orchestrator powinien zarządzać daną grupą repozytoriów. REST nie zapewnia transakcji obejmującej odczyt PR-a i POST komentarza, więc pozostaje niewielkie okno zmiany danych między tymi operacjami. Przy częściowej awarii wcześniej zapisane przydziały pozostają i zostaną odczytane w następnym buildzie.

Testy lokalne obejmują round-trip markera, ponowny przebieg bez dodatkowego POST, PUT przy rozszerzeniu listy, odrzucenie nieznanych reviewerów, obcych autorów i wielu markerów oraz zatrzymanie zapisu po zmianie source. Testy nie zastępują przebiegu integracyjnego na Jenkinsie/Bitbuckecie.

Źródło endpointów komentarzy i activities: https://docs.atlassian.com/bitbucket-server/rest/7.6.6/bitbucket-rest.html
