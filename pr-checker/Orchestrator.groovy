/* Domain records are plain serializable maps with a type/schemaVersion.
 * This loaded Pipeline script has no mutable instance fields.
 * Collectors write separate files; only the sequential planner changes scores.
 */
def loadConfiguration(String path) {
    if (!fileExists(path)) { error("Missing configuration: ${path}") }
    return validateConfiguration(readYaml(file: path))
}

def requiredText(def value, String name) {
    if (!(value instanceof String) || !value.trim()) { error("${name}: non-empty string required") }
    return value.trim()
}

def positiveInteger(def value, String name, int minimum) {
    if (!(value instanceof Integer) || value < minimum) { error("${name}: integer >= ${minimum} required") }
    return value
}

def validateConfiguration(def cfg) {
    if (!(cfg instanceof Map) || cfg['schemaVersion'] != 2) { error('Expected configuration schemaVersion: 2') }
    if (!(cfg['bitbucket'] instanceof Map) || !(cfg['policy'] instanceof Map)) {
        error('bitbucket and policy mappings are required')
    }
    if (!(cfg['notifications'] instanceof Map) || !(cfg['notifications']['previewOnly'] instanceof Boolean)) { error('notifications.previewOnly boolean required') }
    String statePath = requiredText(cfg['notifications']['stateDirectory'], 'notifications.stateDirectory')
    if (!statePath.startsWith('/') || statePath.contains('..')) { error('Notification state directory must be an absolute persistent Unix path') }
    validateBranchPatterns(cfg['targetBranchPatterns'], 'targetBranchPatterns')
    Map bb = cfg['bitbucket']
    bb['url'] = requiredText(bb['url'], 'bitbucket.url').replaceAll('/+$', '')
    if (!(bb['url'] ==~ 'https?://[^\\s?#]+')) { error('bitbucket.url must be an HTTP(S) base URL') }
    bb['credentialsId'] = requiredText(bb['credentialsId'], 'bitbucket.credentialsId')
    positiveInteger(cfg['lookbackDays'], 'lookbackDays', 1)
    positiveInteger(cfg['mergedPageSize'], 'mergedPageSize', 1)
    if (!(cfg['scoring'] instanceof Map) || !(cfg['scoring']['fastReview'] instanceof Map) || !(cfg['scoring']['sizeRules'] instanceof List)) {
        error('scoring requires baseReviewPoints, fastReview and sizeRules')
    }
    if (cfg.containsKey('baseReviewPoints') || cfg.containsKey('fastReview')) { error('Move legacy scoring fields into scoring') }
    positiveInteger(cfg['scoring']['baseReviewPoints'], 'scoring.baseReviewPoints', 0)
    for (String key : ['bonusPoints', 'smallHours', 'largeHours']) {
        positiveInteger(cfg['scoring']['fastReview'][key], 'scoring.fastReview.' + key, key == 'bonusPoints' ? 0 : 1)
    }
    List ruleIds = []
    for (def rule : cfg['scoring']['sizeRules']) {
        if (!(rule instanceof Map)) { error('Each size rule must be a mapping') }
        String id = requiredText(rule['id'], 'sizeRule.id')
        if (ruleIds.contains(id)) { error('Duplicate size rule ID') }
        ruleIds.add(id)
        if (!(rule['metric'] in ['changedLines', 'changedFiles'])) { error('Size metric must be changedLines or changedFiles') }
        positiveInteger(rule['min'], 'sizeRule.min', 0)
        positiveInteger(rule['points'], 'sizeRule.points', 0)
        if (rule.containsKey('max')) {
            positiveInteger(rule['max'], 'sizeRule.max', 0)
            if (rule['max'] < rule['min']) { error('sizeRule.max must be >= min') }
        }
    }
    for (String key : ['largePrThreshold', 'ownTeamSmallReviewers', 'ownTeamLargeReviewers']) {
        positiveInteger(cfg['policy'][key], "policy.${key}", 1)
    }
    if (!(cfg['teams'] instanceof List) || !(cfg['repositories'] instanceof List) || cfg['repositories'].isEmpty()) {
        error('teams and non-empty repositories lists are required')
    }
    Map teams = [:]
    List members = []
    for (def team : cfg['teams']) {
        if (!(team instanceof Map)) { error('Each team must be a mapping') }
        String id = requiredText(team['id'], 'team.id')
        if (teams.containsKey(id)) { error("Duplicate team: ${id}") }
        validateTeam(team['members'])
        teams[id] = true
        for (Map member : team['members']) {
            members.add([lanId: member['lanId'].trim(), email: member['email'].trim(), teamId: id,
                displayName: member['displayName'] ? requiredText(member['displayName'], 'member.displayName') : member['lanId'].trim()])
        }
    }
    validateTeam(members) // global identity uniqueness, including across teams
    Map repoIds = [:]
    Map repoPaths = [:]
    for (def repo : cfg['repositories']) {
        if (!(repo instanceof Map)) { error('Each repository must be a mapping') }
        for (String key : ['id', 'projectKey', 'slug']) {
            repo[key] = requiredText(repo[key], "repository.${key}")
        }
        if (!(repo['id'] ==~ '[a-z0-9][a-z0-9_-]*')) { error('Repository id must be a safe lowercase file identifier') }
        if (!(repo['codeownerTeams'] instanceof List) || repo['codeownerTeams'].isEmpty()) {
            error('Each repository needs a non-empty codeownerTeams list')
        }
        if (repo.containsKey('targetBranchPatterns')) {
            validateBranchPatterns(repo['targetBranchPatterns'], 'repository.targetBranchPatterns')
        }
        List owners = []
        for (def owner : repo['codeownerTeams']) {
            if (!(owner instanceof String) || !teams.containsKey(owner) || owners.contains(owner)) {
                error('codeownerTeams must contain unique, declared team IDs')
            }
            owners.add(owner)
        }
        String path = normalized(repo['projectKey']) + '/' + normalized(repo['slug'])
        if (repoIds.containsKey(repo['id']) || repoPaths.containsKey(path)) { error('Duplicate repository') }
        repoIds[repo['id']] = true
        repoPaths[path] = true
    }
    cfg['members'] = members
    return cfg
}

def repositoryBranch(Map config, Map repo, long now) {
    // Method arguments give every closure its own repository binding.
    return {
        dir("pr-checker-output/repos/${repo['id']}") {
            withCredentials([usernamePassword(credentialsId: config['bitbucket']['credentialsId'],
                usernameVariable: 'GIT_USER', passwordVariable: 'GIT_PASS')]) {
                try {
                    echo "[${repo['id']}] Collecting ${repo['projectKey']}/${repo['slug']}"
                    Map snapshot = collectRepository(config, repo, now)
                    writeJSON(file: 'snapshot.json', json: snapshot, pretty: 2)
                    echo "[${repo['id']}] Collection completed"
                } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException interrupted) {
                    echo "[${repo['id']}] Collection interrupted or timed out"
                    throw interrupted
                } catch (Exception failure) {
                    // Keep the original exception and log while credentials masking is active.
                    echo "[${repo['id']}] COLLECTION FAILED: ${failure.message ?: 'No exception message supplied'}"
                    throw failure
                }
            }
        }
    }
}

// Glob patterns: * matches any sequence (including /); ? matches one character.
// All other characters are literal, and the whole short target branch must match.
def validateBranchPatterns(def patterns, String field) {
    if (!(patterns instanceof List)) { error("${field}: list required") }
    for (def pattern : patterns) {
        if (!(pattern instanceof String) || !pattern || pattern != pattern.trim() || pattern.startsWith('refs/')) {
            error("${field}: use non-empty short branch patterns, e.g. develop or release/*")
        }
    }
}

def matchesTargetBranch(Map pr, List patterns) {
    String ref = pr['toRef']?.get('id')
    String branch = ref ? (ref.startsWith('refs/heads/') ? ref.substring(11) : null) : pr['toRef']?.get('displayId')
    if (!branch) { return false }
    for (String pattern : patterns) {
        String regex = '^'
        for (int i = 0; i < pattern.length(); i++) {
            String character = pattern.substring(i, i + 1)
            if (character == '*') { regex += '.*' }
            else if (character == '?') { regex += '.' }
            else {
                if ('\\.^$|()[]{}+'.contains(character)) { regex += '\\' }
                regex += character
            }
        }
        if (branch ==~ (regex + '$')) { return true }
    }
    return false
}

def collectRepository(Map config, Map repo, long now) {
    String base = config['bitbucket']['url']
    String api = "${base}/rest/api/latest/projects/${repo['projectKey']}/repos/${repo['slug']}"
    List patterns = repo.containsKey('targetBranchPatterns') ? repo['targetBranchPatterns'] : config['targetBranchPatterns']
    List open = []
    List merged = []
    echo "[${repo['id']}] Fetching OPEN PRs"
    for (Map pr : fetchOpenPullRequests(api + '/pull-requests')) {
        if (matchesTargetBranch(pr, patterns)) { open.add(collectPullRequest(config, repo, pr, api)) }
    }
    echo "[${repo['id']}] Fetching MERGED PRs"
    for (Map pr : fetchRecentMergedPullRequests(api + '/pull-requests',
        config['mergedPageSize'] as Integer, now, config['lookbackDays'] as Integer)) {
        if (matchesTargetBranch(pr, patterns)) { merged.add(collectPullRequest(config, repo, pr, api)) }
    }
    return [type: 'RepositorySnapshot', schemaVersion: 1, repositoryId: repo['id'],
        collectedAt: now, openPullRequests: open, mergedPullRequests: merged]
}

def collectPullRequest(Map config, Map repo, Map pr, String repoApi) {
    String prApi = "${repoApi}/pull-requests/${pr['id']}"
    Map authorUser = pr['author']?.get('user') ?: [:]
    Map author = findTeamMember(authorUser, config['members'])
    List approvals = []
    List reviewers = []
    for (Map row : teamReviewers(pr, config['members'])) {
        String id = normalized(row['member']['lanId'])
        reviewers.add(id)
        if (row['approved'] && (author == null || id != normalized(author['lanId']))) { approvals.add(id) }
    }
    echo "[${repo['id']}] PR #${pr['id']}: reading diff stats"
    Map diff = executeBitbucketGet(prApi + '/diff-stats-summary/')
    long added = readLineCount(diff, ['totalInsertions', 'addedLines', 'linesAdded', 'totalLinesAdded'], 'added lines')
    long deleted = readLineCount(diff, ['totalDeletions', 'deletedLines', 'linesDeleted', 'totalLinesDeleted'], 'deleted lines')
    Long changedFiles = null
    for (Map rule : config['scoring']['sizeRules']) {
        if (rule['metric'] == 'changedFiles') {
            changedFiles = readLineCount(diff, ['modifiedFiles', 'filesModified', 'filesChanged', 'changedFiles', 'totalFilesChanged'], 'changed files')
            break
        }
    }
    Map sizeAward = calculateSizeAward(config['scoring'], added + deleted, changedFiles)
    long size = sizeAward['points'] as Long
    Map dto = [type: 'PullRequest', key: "${repo['id']}#${pr['id']}".toString(),
        repositoryId: repo['id'], repositoryName: repo['slug'], id: pr['id'], title: pr['title'], state: pr['state'],
        version: pr['version'], targetBranch: pr['toRef']?.get('displayId') ?: pr['toRef']?.get('id'), createdDate: pr['createdDate'], closedDate: pr['closedDate'],
        url: "${config['bitbucket']['url']}/projects/${repo['projectKey']}/repos/${repo['slug']}/pull-requests/${pr['id']}/overview".toString(),
        author: [id: author == null ? null : normalized(author['lanId']),
            name: authorUser['displayName'] ?: authorUser['name'] ?: 'UNKNOWN',
            teamId: author == null ? null : author['teamId']],
        codeownerTeams: repo['codeownerTeams'], reviewers: reviewers, approvedBy: approvals,
        diff: [added: added, deleted: deleted, changed: added + deleted, changedFiles: changedFiles, sizeRule: sizeAward['ruleId'], sizePoints: size],
        reviewPoints: (config['scoring']['baseReviewPoints'] as Long) + size,
        sourceCommit: pr['fromRef']?.get('latestCommit'), targetCommit: pr['toRef']?.get('latestCommit')]
    dto['reviewerDetails'] = reviewerDetails(pr, config['members'])
    dto['reviewActivity'] = collectReviewActivity(prApi, pr, config['members'])
    if (pr['state'] == 'OPEN') {
        dto['assignment'] = readExistingAssignment(prApi, config)
        dto['build'] = collectBuildStatus(config, pr)
        dto['merge'] = collectMergeStatus(prApi)
        // Avoid mixing a diff for one revision with builds for another.
        Map fresh = executeBitbucketGet(prApi)
        if (fresh['toRef']?.get('id') != pr['toRef']?.get('id') || fresh['state'] != 'OPEN' || fresh['draft'] == true || fresh['version'] != pr['version'] ||
            fresh['fromRef']?.get('latestCommit') != dto['sourceCommit'] ||
            fresh['toRef']?.get('latestCommit') != dto['targetCommit']) {
            error("PR ${dto['key']} changed during collection. Retry for a consistent plan.")
        }
    } else {
        dto['projectVersion'] = collectProjectVersion(config, pr)
    }
    return dto
}

def reviewerDetails(Map pr, List members) {
    Map rows = [:]
    List participants = []
    participants.addAll(pr['participants'] ?: [])
    participants.addAll(pr['reviewers'] ?: [])
    for (Map person : participants) {
        if (person['role'] == 'AUTHOR') { continue }
        Map user = person['user'] ?: [:]
        Map member = findTeamMember(user, members)
        String id = member == null ? normalized(user['name'] ?: user['emailAddress']) : normalized(member['lanId'])
        if (id) {
            rows[id] = [id: id, name: user['displayName'] ?: (member == null ? user['name'] : member['displayName']) ?: id,
                status: reviewStatus(person), lastReviewedCommit: person['lastReviewedCommit']]
        }
    }
    return rows.values().toList()
}

def collectProjectVersion(Map config, Map pr) {
    Map source = pr['fromRef']?.get('repository') ?: [:]
    String commit = pr['fromRef']?.get('latestCommit')
    Map result = [value: null, commit: commit, status: 'UNKNOWN']
    if (!commit || !source['project']?.get('key') || !source['slug']) { return result }
    try {
        String url = "${config['bitbucket']['url']}/rest/api/latest/projects/${source['project']['key']}/repos/${source['slug']}/raw/gradle.properties?at=${commit}"
        String content = executeBitbucketTextGet(url)
        Map props = readProperties(text: content, interpolate: false)
        String value = props['version'] == null ? '' : props['version'].toString().trim()
        result['value'] = value ?: null
        result['status'] = value ? 'AVAILABLE' : 'MISSING_VERSION'
    } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException interrupted) {
        throw interrupted
    } catch (Exception ignored) {
        result['status'] = 'UNAVAILABLE'
        echo "PR #${pr['id']}: gradle.properties version unavailable at source commit."
    }
    return result
}

def trackedActions(List activities, List members) {
    List result = []
    for (Map activity : activities) {
        if (activity['createdDate'] == null) { continue }
        Map member = findTeamMember(activity['user'] ?: [:], members)
        String action = activity['action']
        if (action in ['APPROVED', 'UNAPPROVED', 'REVIEWED', 'NEEDS_WORK', 'CHANGES_REQUESTED', 'COMMENTED', 'RESCOPED', 'OPENED', 'UPDATED']) {
            result.add([action: action, at: activity['createdDate'] as Long,
                memberId: member == null ? null : normalized(member['lanId']),
                fromHash: activity['fromHash'], previousFromHash: activity['previousFromHash'],
                draft: activity['draft'] instanceof Boolean ? activity['draft'] : null,
                commentAction: activity['commentAction']])
        }
    }
    return result
}

def readExistingAssignment(String prApi, Map config) {
    String botUsername = requiredText(env.GIT_USER, 'GIT_USER from Jenkins credentials')
    String marker = '[jenkins-pr-review:v1]'
    try {
        List activities = fetchPagedValues(prApi + '/activities')
        Map candidates = [:]
        for (Map activity : activities) {
            Map comment = activity['comment'] ?: [:]
            if (activity['action'] == 'COMMENTED' &&
                normalized(comment['author']?.get('name')) == normalized(botUsername) &&
                (comment['text'] ?: '').toString().startsWith('[jenkins-pr-review:')) {
                if (comment['id'] == null) { return [status:'INVALID',reviewers:[]] }
                candidates[comment['id'].toString()] = true
            }
        }
        if (candidates.isEmpty()) { return [status:'NONE', source:'COMMENT', reviewers:[]] }
        if (candidates.size() != 1) { return [status:'AMBIGUOUS',reviewers:[]] }
        String commentId = candidates.keySet().toList()[0]
        // Activities can contain old versions. Read the actual current comment before parsing.
        Map comment = executeBitbucketGet(prApi + '/comments/' + commentId)
        if (normalized(comment['author']?.get('name')) != normalized(botUsername)) {
            return [status:'INVALID',reviewers:[]]
        }
        List lines = (comment['text'] ?: '').toString().readLines()
        if (lines.size() < 2 || lines[0] != marker || comment['version'] == null) { return [status:'INVALID',reviewers:[]] }
        def payload = readJSON(text:lines[1], returnPojo:true)
        if (!(payload instanceof Map) || payload['schemaVersion'] != 1 || !(payload['reviewers'] instanceof List) || payload['reviewers'].isEmpty()) {
            return [status:'INVALID',reviewers:[]]
        }
        List reviewers = []
        for (def id : payload['reviewers']) {
            if (!(id instanceof String) || reviewers.contains(normalized(id))) { return [status:'INVALID',reviewers:[]] }
            Map member = findTeamMember([name:id],config['members'])
            if (member == null) { return [status:'INVALID',reviewers:[]] }
            reviewers.add(normalized(member['lanId']))
        }
        return [status:'KNOWN',source:'COMMENT',reviewers:reviewers,commentId:comment['id'],commentVersion:comment['version']]
    } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException interrupted) { throw interrupted }
    catch (Exception ignored) {
        echo 'Assignment marker unavailable; this PR will require manual attention.'
        return [status:'UNKNOWN',source:'COMMENT',reviewers:[]]
    }
}

def writeAssignmentComment(String api, String method, Map payload) {
    writeJSON(file:'assignment-comment-request.json',json:payload)
    String response
    withEnv(["PR_CHECKER_URL=${api}", "PR_CHECKER_METHOD=${method}"]) {
        response = sh(returnStdout:true,label:'Save assignment comment',script:'''
            set +x
            curl --silent --show-error --fail --connect-timeout 15 --max-time 120 \
                --user "$GIT_USER:$GIT_PASS" --request "$PR_CHECKER_METHOD" \
                --header 'Content-Type: application/json' --header 'Accept: application/json' \
                --data-binary @assignment-comment-request.json --url "$PR_CHECKER_URL"
        ''').trim()
    }
    return readJSON(text:response,returnPojo:true)
}

def persistAssignments(Map config, Map plan) {
    Map repos = [:]
    for (Map repo : config['repositories']) { repos[repo['id']] = repo }
    Map prs = [:]
    for (Map pr : plan['openPullRequests']) { prs[pr['key']] = pr }
    for (Map assignment : plan['assignments']) {
        if (assignment['status'] == 'MANUAL') { continue }
        Map pr = prs[assignment['prKey']]
        Map repo = repos[pr['repositoryId']]
        String api = "${config['bitbucket']['url']}/rest/api/latest/projects/${repo['projectKey']}/repos/${repo['slug']}/pull-requests/${pr['id']}"
        Map currentMarker = readExistingAssignment(api,config)
        if (currentMarker != pr['assignment']) { error("Assignment marker changed for ${pr['key']}; retry collection") }
        List reviewers = assignedPeople(assignment)
        if (reviewers.isEmpty()) { continue }
        if (currentMarker['status'] == 'KNOWN' && currentMarker['reviewers'].toSet() == reviewers.toSet()) {
            assignment['persisted'] = true
            continue
        }
        if (!(currentMarker['status'] in ['KNOWN','NONE'])) { error('Refusing to overwrite uncertain assignment marker') }
        Map fresh = executeBitbucketGet(api)
        if (fresh['state'] != 'OPEN' || fresh['draft'] == true || fresh['version'] != pr['version'] ||
            fresh['fromRef']?.get('latestCommit') != pr['sourceCommit'] || fresh['toRef']?.get('latestCommit') != pr['targetCommit'] ||
            (fresh['toRef']?.get('displayId') ?: fresh['toRef']?.get('id')) != pr['targetBranch']) {
            error("PR ${pr['key']} changed before assignment; retry collection")
        }
        String json = writeJSON(returnText:true,json:[schemaVersion:1,reviewers:reviewers])
        List names = []
        for (String id : reviewers) { names.add(plan['members'][id]['displayName'] ?: id) }
        String text = '[jenkins-pr-review:v1]\n' + json.replace('\n','') + '\n\nAssigned reviewers: ' + names.join(', ')
        Map payload = [text:text]
        String url = api + '/comments'
        String method = 'POST'
        if (currentMarker['status'] == 'KNOWN') {
            method = 'PUT'; url += '/' + currentMarker['commentId']; payload['version'] = currentMarker['commentVersion']
        }
        writeAssignmentComment(url,method,payload)
        Map saved = readExistingAssignment(api,config)
        if (saved['status'] != 'KNOWN' || saved['reviewers'].toSet() != reviewers.toSet()) {
            error("Could not verify assignment comment for ${pr['key']}; no email will be sent")
        }
        pr['assignment'] = saved
        assignment['persisted'] = true
    }
    plan['mode'] = 'ASSIGNMENTS_PERSISTED'
}

// Decision state comes from reviewers; timestamps from published PR activities.
// Bitbucket can expose NEEDS_WORK without publishing a REVIEWED activity.
def reviewStatus(Map reviewer) {
    String status = reviewer['status'] ?: ''
    if (status in ['NEEDS_WORK', 'CHANGES_REQUESTED']) { return 'CHANGES_REQUESTED' }
    if (status == 'APPROVED' || reviewer['approved'] == true) { return 'APPROVED' }
    if (status in ['', 'UNAPPROVED']) { return 'PENDING' }
    return 'UNKNOWN'
}

def collectReviewActivity(String prApi, Map pr, List members) {
    List activities = []
    String availability = 'AVAILABLE'
    try {
        activities = fetchPagedValues(prApi + '/activities')
    } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException interrupted) {
        throw interrupted
    } catch (Exception ignored) {
        availability = 'UNKNOWN'
        echo "PR #${pr['id']}: activity history unavailable. Review state/revision comparison retained."
    }
    return summarizeReviewActivity(pr, members, activities, availability)
}

def laterTimestamp(def current, def candidate) {
    if (candidate == null) { return current }
    long time = candidate as Long
    return current == null || time > (current as Long) ? time : current
}

def recordCommentDates(Map comment, List members, Map states) {
    // Iterative traversal includes published replies without depending on nesting depth.
    List queue = [comment]
    int cursor = 0
    while (cursor < queue.size()) {
        Map item = queue[cursor++]
        Map member = findTeamMember(item['author'] ?: [:], members)
        if (member != null) {
            Map state = states[normalized(member['lanId'])]
            state['lastCommentAt'] = laterTimestamp(state['lastCommentAt'], item['createdDate'])
        }
        queue.addAll(item['comments'] ?: [])
    }
}

def summarizeReviewActivity(Map pr, List members, List activities, String availability) {
    Map states = [:]
    for (Map member : members) {
        states[normalized(member['lanId'])] = [status: 'PENDING', lastReviewedCommit: null,
            approvedAt: null, changesRequestedAt: null, lastCommentAt: null, lastReviewAt: null,
            lastActivityAt: null, reviewedRevisionChanged: null, needsAnotherLook: null]
    }
    for (Map reviewer : (pr['reviewers'] ?: [])) {
        Map member = findTeamMember(reviewer['user'] ?: [:], members)
        if (member != null) {
            Map state = states[normalized(member['lanId'])]
            state['status'] = reviewStatus(reviewer)
            state['lastReviewedCommit'] = reviewer['lastReviewedCommit']
        }
    }
    String head = pr['fromRef']?.get('latestCommit')
    Long lastSourceChangeAt = null
    boolean addedCommits = false
    for (Map activity : activities) {
        String action = activity['action']
        def time = activity['createdDate']
        // Target-only rescopes do not mean the author pushed new source changes.
        // Match the current head; an unrelated historic rescope cannot date this head.
        if (action == 'RESCOPED' && head && activity['fromHash'] == head &&
            activity['previousFromHash'] && activity['previousFromHash'] != head && time != null) {
            if (lastSourceChangeAt == null || (time as Long) > lastSourceChangeAt) {
                lastSourceChangeAt = time as Long
                addedCommits = ((activity['added']?.get('total') ?: 0) as Long) > 0L
            }
        }
        Map member = findTeamMember(activity['user'] ?: [:], members)
        if (member != null && time != null) {
            Map state = states[normalized(member['lanId'])]
            if (action == 'APPROVED') { state['approvedAt'] = laterTimestamp(state['approvedAt'], time) }
            if (action in ['REVIEWED', 'NEEDS_WORK', 'CHANGES_REQUESTED']) {
                state['changesRequestedAt'] = laterTimestamp(state['changesRequestedAt'], time)
            }
            if (action == 'COMMENTED' && activity['commentAction'] in ['ADDED', 'REPLIED', 'UPDATED', 'EDITED']) {
                state['lastCommentAt'] = laterTimestamp(state['lastCommentAt'], time)
            }
        }
        if (action == 'COMMENTED' && activity['commentAction'] != 'DELETED' && activity['comment'] instanceof Map) {
            recordCommentDates(activity['comment'], members, states)
        }
    }
    for (Map state : states.values()) {
        if (state['status'] == 'APPROVED') { state['lastReviewAt'] = state['approvedAt'] }
        if (state['status'] == 'CHANGES_REQUESTED') { state['lastReviewAt'] = state['changesRequestedAt'] }
        // Retain past decisions after automatic approval reset, for chronology only.
        def lastDecision = laterTimestamp(state['approvedAt'], state['changesRequestedAt'])
        state['lastActivityAt'] = laterTimestamp(lastDecision, state['lastCommentAt'])
        if (head && state['lastReviewedCommit']) {
            state['reviewedRevisionChanged'] = state['lastReviewedCommit'] != head
        }
        if (state['reviewedRevisionChanged'] == false && state['status'] in ['APPROVED', 'CHANGES_REQUESTED']) {
            state['needsAnotherLook'] = false
        } else if (lastSourceChangeAt != null && state['lastActivityAt'] != null) {
            state['needsAnotherLook'] = lastSourceChangeAt > (state['lastActivityAt'] as Long)
        } else if (state['reviewedRevisionChanged'] == true) {
            state['needsAnotherLook'] = true
        }
    }
    return [historyStatus: availability, actions: trackedActions(activities, members), lastSourceChangeAt: lastSourceChangeAt,
        sourceChangeIncludesAddedCommits: addedCommits, byMember: states]
}

def fetchPagedValues(String url) {
    List result = []
    int start = 0
    while (true) {
        Map page = executeBitbucketGet("${url}?start=${start}&limit=100")
        if (!(page['values'] instanceof List)) { error('Missing API values list') }
        result.addAll(page['values'])
        if (page['isLastPage'] == true) { break }
        if (page['nextPageStart'] == null) { error('Missing API nextPageStart') }
        int next = page['nextPageStart'] as Integer
        if (next <= start) { error('API pagination did not advance') }
        start = next
    }
    return result
}

def collectBuildStatus(Map config, Map pr) {
    Map ref = pr['fromRef'] ?: [:]
    Map source = ref['repository'] ?: [:]
    String project = source['project']?.get('key')
    String slug = source['slug']
    String commit = ref['latestCommit']
    if (!project || !slug || !commit) { return [state: 'UNKNOWN', observed: null, entries: [], reason: 'Missing source revision'] }
    try {
        String url = "${config['bitbucket']['url']}/rest/api/latest/projects/${project}/repos/${slug}/commits/${commit}/builds"
        return summarizeBuilds(fetchPagedValues(url))
    } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException interrupted) {
        throw interrupted
    } catch (Exception ignored) {
        echo "Build status unavailable for PR #${pr['id']}"
        return [state: 'UNKNOWN', observed: null, entries: [], reason: 'Build API unavailable or response invalid']
    }
}

def summarizeBuilds(List entries) {
    Map latest = [:]
    for (Map entry : entries) {
        if (!entry['key'] || !entry['state']) { error('Invalid build status entry') }
        String key = entry['key'].toString()
        long time = (entry['updatedDate'] ?: entry['dateAdded'] ?: 0L) as Long
        if (!latest.containsKey(key) || time > (latest[key]['timestamp'] as Long)) {
            latest[key] = [key: key, state: entry['state'], url: entry['url'], timestamp: time]
        }
    }
    List statuses = []
    for (Map entry : latest.values()) { statuses.add(entry['state']) }
    String state = 'SUCCESSFUL'
    if (statuses.isEmpty()) { state = 'NO_STATUS' }
    else if (statuses.contains('FAILED')) { state = 'FAILED' }
    else if (statuses.contains('CANCELLED')) { state = 'CANCELLED' }
    else if (statuses.contains('INPROGRESS')) { state = 'INPROGRESS' }
    else {
        for (String status : statuses) { if (status != 'SUCCESSFUL') { state = 'UNKNOWN' } }
    }
    return [state: state, observed: !entries.isEmpty(), entries: latest.values().toList(),
        reason: state == 'NO_STATUS' ? 'No status published for source commit; trigger cannot be inferred' : null]
}

def collectMergeStatus(String prApi) {
    try {
        Map response = executeBitbucketGet(prApi + '/merge')
        return [canMerge: response['canMerge'] instanceof Boolean ? response['canMerge'] : null,
            conflicted: response['conflicted'] instanceof Boolean ? response['conflicted'] : null,
            outcome: response['outcome'], vetoes: response['vetoes'] ?: [], reason: null]
    } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException interrupted) {
        throw interrupted
    } catch (Exception ignored) {
        echo 'Mergeability unavailable; marking it UNKNOWN.'
        return [canMerge: null, conflicted: null, outcome: null, vetoes: [], reason: 'Merge API unavailable']
    }
}

def executeBitbucketTextGet(String url) {
    String responseBody
    // Shell expands credentials. Neither secrets nor URL are embedded in shell code.
    withEnv(["PR_CHECKER_URL=${url}"]) {
        responseBody = sh(
            label: 'Read Bitbucket API',
            script: '''
                set +x
                curl --silent --show-error --fail \
                    --connect-timeout 15 --max-time 120 \
                    --user "$GIT_USER:$GIT_PASS" \
                    --header 'Accept: */*' \
                    --url "$PR_CHECKER_URL"
            ''',
            returnStdout: true
        ).trim()
    }
    return responseBody
}

def executeBitbucketGet(String url) {
    // Log only the API path and query, never the host's embedded credentials or body.
    int apiStart = url.indexOf('/rest/')
    echo 'Bitbucket GET ' + (apiStart >= 0 ? url.substring(apiStart) : '(API endpoint)')
    def body = readJSON(text: executeBitbucketTextGet(url), returnPojo: true)
    if (!(body instanceof Map)) {
        error('Expected a JSON object from Bitbucket.')
    }
    return body
}

def pageValues(Map page) {
    if (!(page['values'] instanceof List)) {
        error("Bitbucket page is missing its values list. Returned fields: ${page.keySet()}")
    }
    for (def pr : page['values']) {
        if (!(pr instanceof Map) || pr['id'] == null) {
            error('Invalid pull request in Bitbucket response.')
        }
    }
    return page['values']
}

def fetchOpenPullRequests(String apiUrl) {
    List result = []
    int start = 0
    while (true) {
        Map page = executeBitbucketGet("${apiUrl}?state=OPEN&start=${start}&limit=100")
        for (Map pr : pageValues(page)) {
            if (pr['state'] == 'OPEN' && pr['draft'] != true) {
                result.add(pr)
            }
        }
        if (page['isLastPage'] == true) {
            break
        }
        if (page['nextPageStart'] == null) {
            error('Missing nextPageStart on a non-final Bitbucket page.')
        }
        int next = page['nextPageStart'] as Integer
        if (next <= start) {
            error('Bitbucket pagination did not advance.')
        }
        start = next
    }
    return result
}

def fetchRecentMergedPullRequests(String apiUrl, int pageSize, long now, int lookbackDays) {
    if (pageSize <= 0 || lookbackDays <= 0) {
        error('MERGED page size and lookback days must be positive.')
    }
    long cutoff = now - (lookbackDays * 24L * 60L * 60L * 1000L)
    List result = []
    Map seen = [:]
    Long previousClosedDate = null
    int start = 0
    while (true) {
        // NEWEST is not merge-date order. CLOSED_DATE is descending.
        Map page = executeBitbucketGet(
            "${apiUrl}?state=MERGED&order=CLOSED_DATE&start=${start}&limit=${pageSize}")
        List values = pageValues(page)
        boolean outsideWindow = false
        for (Map pr : values) {
            if (pr['state'] != 'MERGED' || pr['closedDate'] == null) {
                error('Expected a MERGED PR with closedDate; cannot safely apply lookback cutoff.')
            }
            long closedDate = pr['closedDate'] as Long
            if (previousClosedDate != null && closedDate > previousClosedDate) {
                error('MERGED results are not in descending CLOSED_DATE order. Retry the job.')
            }
            previousClosedDate = closedDate
            if (closedDate < cutoff) {
                outsideWindow = true
            }
            String id = pr['id'].toString()
            if (closedDate >= cutoff && closedDate <= now && !seen.containsKey(id)) {
                result.add(pr)
                seen[id] = true
            }
        }
        echo "MERGED page start=${start}: fetched ${values.size()}, in lookback so far=${result.size()}"
        // Validate the entire boundary page before stopping. The exact cutoff is included.
        if (outsideWindow || page['isLastPage'] == true) {
            break
        }
        if (page['nextPageStart'] == null) {
            error('Missing nextPageStart on a non-final MERGED page.')
        }
        int next = page['nextPageStart'] as Integer
        if (next <= start) {
            error('MERGED pagination did not advance.')
        }
        start = next
    }
    return result
}

def normalized(def value) {
    return value == null ? '' : value.toString().trim().toLowerCase()
}

def validateTeam(def members) {
    if (!(members instanceof List)) {
        error('teamMembers must be a YAML list (use [] for an empty team).')
    }
    List ids = []
    List emails = []
    for (def member : members) {
        if (!(member instanceof Map) || !(member['lanId'] instanceof String) ||
            !(member['email'] instanceof String)) {
            error('Every teamMembers entry must contain string lanId and email fields.')
        }
        String id = normalized(member['lanId'])
        String email = normalized(member['email'])
        if (!id || !email || ids.contains(id) || emails.contains(email)) {
            error('Team LAN IDs and emails must be non-empty and unique (case-insensitive).')
        }
        ids.add(id)
        emails.add(email)
    }
}

def findTeamMember(Map user, List members) {
    String id = normalized(user['name'])
    String email = normalized(user['emailAddress'])
    Map match = null
    for (Map member : members) {
        if ((id && id == normalized(member['lanId'])) ||
            (email && email == normalized(member['email']))) {
            if (match != null && match['lanId'] != member['lanId']) {
                error('Ambiguous team identity: LAN ID and email match different members.')
            }
            match = member
        }
    }
    return match
}

def teamReviewers(Map pr, List members) {
    // Only actual reviewers, as in the original POC. One row per team member.
    Map rows = [:]
    for (Map reviewer : (pr['reviewers'] ?: [])) {
        Map user = reviewer['user'] ?: [:]
        Map member = findTeamMember(user, members)
        if (member != null) {
            String key = normalized(member['lanId'])
            boolean approved = reviewStatus(reviewer) == 'APPROVED'
            if (!rows.containsKey(key) || approved) {
                rows[key] = [member: member, approved: approved,
                    name: user['displayName'] ?: user['name'] ?: member['lanId']]
            }
        }
    }
    return rows.values().toList()
}

def readLineCount(Map stats, List aliases, String description) {
    for (String key : aliases) {
        if (stats[key] != null) {
            String value = stats[key].toString()
            if (!(value ==~ '[0-9]+')) {
                error("Invalid ${description} field: ${key}")
            }
            return value as Long
        }
    }
    // Never silently turn an unrecognized API response into a zero size score.
    error("Missing ${description} in diff stats. Returned fields: ${stats.keySet()}")
}

def calculateSizeAward(Map scoring, long changedLines, Long changedFiles) {
    Map best = [points: 0L, ruleId: null]
    for (Map rule : scoring['sizeRules']) {
        Long value = rule['metric'] == 'changedLines' ? changedLines : changedFiles
        if (value == null) { error('Missing changed files required by scoring rules') }
        if (value >= (rule['min'] as Long) && (!rule.containsKey('max') || value <= (rule['max'] as Long))) {
            if (best['ruleId'] == null || rule['points'] > best['points']) {
                best = [points: rule['points'] as Long, ruleId: rule['id']]
            }
        }
    }
    return best
}

// ---------- Global model and deterministic assignment planning ----------
def reviewAward(Map pr, String id, Map config) {
    Map history = pr['reviewActivity'] ?: [:]
    Long start = null
    Long approved = null
    Long reset = null
    for (Map event : (history['actions'] ?: [])) {
        if ((event['action'] == 'RESCOPED' && event['fromHash'] == pr['sourceCommit'] &&
            event['previousFromHash'] && event['previousFromHash'] != event['fromHash']) ||
            (event['action'] == 'UPDATED' && event['draft'] == false)) {
            start = laterTimestamp(start, event['at'])
        }
        if (event['memberId'] == id) {
            if (event['action'] == 'APPROVED') { approved = laterTimestamp(approved, event['at']) }
            if (event['action'] in ['UNAPPROVED', 'REVIEWED', 'NEEDS_WORK', 'CHANGES_REQUESTED']) {
                reset = laterTimestamp(reset, event['at'])
            }
        }
    }
    boolean large = pr['diff']['changed'] >= config['policy']['largePrThreshold']
    long limit = (config['scoring']['fastReview'][large ? 'largeHours' : 'smallHours'] as Long) * 3600000L
    Map state = history['byMember']?.get(id) ?: [:]
    boolean known = history['historyStatus'] == 'AVAILABLE' && start != null && approved != null
    boolean currentRevision = !state['lastReviewedCommit'] || state['lastReviewedCommit'] == pr['sourceCommit']
    boolean earned = known && currentRevision && approved >= start && approved - start <= limit &&
        (reset == null || reset < approved)
    long bonus = earned ? config['scoring']['fastReview']['bonusPoints'] as Long : 0L
    return [startedAt: start, approvedAt: approved, limitMillis: limit,
        elapsedMillis: known ? approved - start : null, bonusPoints: bonus,
        totalPoints: (pr['reviewPoints'] as Long) + bonus,
        reason: earned ? 'FAST_REVIEW' : (known ? 'NOT_ELIGIBLE' : 'TIMING_UNKNOWN')]
}

def aggregateAndPlan(Map config, List snapshots, long now) {
    Map members = [:]
    Map scores = [:]
    for (Map member : config['members']) {
        String id = normalized(member['lanId'])
        members[id] = member
        scores[id] = [score: 0L, reviews: 0L, sizePoints: 0L, bonusPoints: 0L,
            openPoints: 0L, proposedPoints: 0L, effectiveScore: 0L]
    }
    Map snapshotsByRepo = [:]
    Map uniquePrs = [:]
    List open = []
    List merged = []
    for (Map snapshot : snapshots) {
        String repoId = snapshot['repositoryId']
        if (snapshot['schemaVersion'] != 1 || snapshot['collectedAt'] != now || snapshotsByRepo.containsKey(repoId)) {
            error('Inconsistent, duplicate or stale repository snapshot')
        }
        snapshotsByRepo[repoId] = snapshot
        for (String field : ['openPullRequests', 'mergedPullRequests']) {
            for (Map pr : snapshot[field]) {
                if (pr['repositoryId'] != repoId || uniquePrs.containsKey(pr['key'])) {
                    error('Duplicate or mismatched PR snapshot')
                }
                uniquePrs[pr['key']] = true
                if (field == 'openPullRequests') { open.add(pr) } else { merged.add(pr) }
            }
        }
    }
    if (snapshotsByRepo.size() != config['repositories'].size()) { error('Incomplete repository collection') }
    for (Map repo : config['repositories']) {
        if (!snapshotsByRepo.containsKey(repo['id'])) { error("Missing snapshot: ${repo['id']}") }
    }
    for (Map pr : (open + merged)) {
        pr['reviewAwards'] = [:]
        for (String id : pr['approvedBy']) { pr['reviewAwards'][id] = reviewAward(pr, id, config) }
    }
    for (Map pr : merged) {
        for (String id : pr['approvedBy']) {
            Map score = scores[id]
            score['score'] = (score['score'] as Long) + (pr['reviewAwards'][id]['totalPoints'] as Long)
            score['bonusPoints'] += pr['reviewAwards'][id]['bonusPoints']
            score['reviews'] += 1L
            score['sizePoints'] = (score['sizePoints'] as Long) + (pr['diff']['sizePoints'] as Long)
        }
    }
    // Reserve all existing commitments BEFORE choosing any new reviewer.
    // Open approvals also represent work already done, even before merge.
    for (Map pr : open) {
        for (String id : committedReviewers(pr)) {
            if (scores.containsKey(id) && id != pr['author']['id']) {
                scores[id]['openPoints'] = (scores[id]['openPoints'] as Long) +
                    ((pr['reviewAwards'][id]?.get('totalPoints') ?: pr['reviewPoints']) as Long)
            }
        }
    }
    for (String id : scores.keySet()) {
        scores[id]['effectiveScore'] = scores[id]['score'] + scores[id]['openPoints']
    }
    // No CPS comparator closures: insertion order by age, then stable PR key.
    open = orderPullRequests(open, 'createdDate', false)
    merged = orderPullRequests(merged, 'closedDate', true)
    List assignments = []
    for (Map pr : open) {
        assignments.add(planPullRequest(pr, config['policy'], members, scores))
    }
    return [type: 'ReviewPlan', schemaVersion: 1, mode: 'PREVIEW_ONLY', collectedAt: now,
        lookbackDays: config['lookbackDays'], scores: scores, members: members,
        openPullRequests: open, mergedPullRequests: merged, assignments: assignments]
}

def committedReviewers(Map pr) {
    List ids = []
    for (String id : pr['approvedBy']) { if (!ids.contains(id)) { ids.add(id) } }
    // Preserve engaged reviewers, including decisions reset after a push.
    // A plain default-reviewer entry without lastReviewedCommit is not engagement.
    Map reviewStates = pr['reviewActivity']?.get('byMember') ?: [:]
    for (String id : reviewStates.keySet()) {
        if ((reviewStates[id]['status'] == 'CHANGES_REQUESTED' || reviewStates[id]['lastReviewedCommit']) && !ids.contains(id)) { ids.add(id) }
    }
    if (pr['assignment']['status'] == 'KNOWN') {
        for (String id : pr['assignment']['reviewers']) { if (!ids.contains(id)) { ids.add(id) } }
    }
    return ids
}

def orderPullRequests(List prs, String dateField, boolean descending) {
    List ordered = []
    for (Map pr : prs) {
        long date = (pr[dateField] ?: 0L) as Long
        int index = 0
        while (index < ordered.size()) {
            Map other = ordered[index]
            long otherDate = (other[dateField] ?: 0L) as Long
            boolean before = descending ? date > otherDate : date < otherDate
            if (before || (date == otherDate && pr['key'].toString().compareTo(other['key'].toString()) < 0)) { break }
            index++
        }
        ordered.add(index, pr)
    }
    return ordered
}

def requiredTeams(Map pr, Map policy) {
    String authorTeam = pr['author']['teamId']
    List owners = pr['codeownerTeams']
    if (!authorTeam) { return [] }
    boolean large = (pr['diff']['changed'] as Long) >= (policy['largePrThreshold'] as Long)
    if (!owners.contains(authorTeam)) { return large ? [[authorTeam], owners] : [[authorTeam]] }
    int count = policy[large ? 'ownTeamLargeReviewers' : 'ownTeamSmallReviewers'] as Integer
    List slots = []
    for (int i = 0; i < count; i++) { slots.add([authorTeam]) }
    return slots
}

def planPullRequest(Map pr, Map policy, Map members, Map scores) {
    List slots = requiredTeams(pr, policy)
    Map plan = [type: 'AssignmentProposal', prKey: pr['key'], eligibleTeamGroups: slots,
        existing: committedReviewers(pr), proposed: [], selected: [], status: 'PREVIEW',
        reason: null, commentState: pr['assignment']['status']]
    if (slots.isEmpty()) {
        plan['status'] = 'MANUAL'
        plan['reason'] = 'Author is not mapped to a configured team'
        return plan
    }
    // A future marker reader must expose malformed/conflicting data explicitly.
    if (!(pr['assignment']['status'] in ['NOT_IMPLEMENTED', 'NONE', 'KNOWN'])) {
        plan['status'] = 'MANUAL'
        plan['reason'] = 'Assignment state is unavailable or ambiguous'
        return plan
    }
    for (List teamIds : slots) {
        String chosen = null
        for (String id : plan['existing']) {
            if (members.containsKey(id) && teamIds.contains(members[id]['teamId']) &&
                id != pr['author']['id'] && !plan['selected'].contains(id)) {
                chosen = id
                break
            }
        }
        if (chosen == null) {
            for (String id : members.keySet()) {
                if (!teamIds.contains(members[id]['teamId']) || id == pr['author']['id'] || plan['selected'].contains(id)) { continue }
                if (chosen == null || scores[id]['effectiveScore'] < scores[chosen]['effectiveScore'] ||
                    (scores[id]['effectiveScore'] == scores[chosen]['effectiveScore'] && id.compareTo(chosen) < 0)) {
                    chosen = id
                }
            }
            if (chosen != null) { plan['proposed'].add(chosen) }
        }
        if (chosen == null) {
            plan['status'] = 'MANUAL'
            plan['reason'] = "Not enough distinct eligible reviewers from ${teamIds}".toString()
            // No partial assignment or partial reservation is committed.
            plan['proposed'] = []
            plan['selected'] = []
            return plan
        }
        plan['selected'].add(chosen)
    }
    for (String id : plan['proposed']) {
        scores[id]['proposedPoints'] = (scores[id]['proposedPoints'] as Long) + (pr['reviewPoints'] as Long)
        scores[id]['effectiveScore'] = (scores[id]['effectiveScore'] as Long) + (pr['reviewPoints'] as Long)
    }
    if (plan['proposed'].isEmpty()) {
        plan['status'] = 'COVERED'
        for (String id : plan['selected']) {
            if (!pr['approvedBy'].contains(id) || pr['reviewActivity']?.get('byMember')?.get(id)?.get('needsAnotherLook') == true) {
                plan['status'] = 'REVIEW_IN_PROGRESS'
            }
        }
    }
    return plan
}

// ---------- Presentation: email bodies and a global overview, no send step ----------
def printScores(Map plan) {
    echo 'LAN_ID | HISTORICAL SCORE | REVIEWS | SIZE POINTS | BONUS | OPEN | PROPOSED | EFFECTIVE'
    for (String id : plan['scores'].keySet()) {
        Map s = plan['scores'][id]
        echo "${id} | ${s['score']} | ${s['reviews']} | ${s['sizePoints']} | ${s['bonusPoints']} | ${s['openPoints']} | ${s['proposedPoints']} | ${s['effectiveScore']}"
    }
}

def html(def value) {
    String text = value == null ? '' : value.toString()
    return text.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
        .replace('"', '&quot;').replace("'", '&#39;')
}

def reportPage(String title, String body) {
    return '<!doctype html><html><head><meta charset="utf-8"><title>' + html(title) +
        '</title></head><body style="font-family:Arial,sans-serif;max-width:1000px;margin:32px auto;color:#172b4d">' +
        '<h1>' + html(title) + '</h1><p><strong>PREVIEW ONLY</strong> — proposals are not saved in Bitbucket. ' +
        'Assignment comments are not read yet. No email has been sent.</p>' + body + '</body></html>'
}

def renderPr(Map pr, Map assignment, String viewerId = null) {
    String title = html(pr['key']) + ' — ' + html(pr['title'])
    String url = pr['url'] == null ? '' : pr['url'].toString()
    String link = url.startsWith('https://') || url.startsWith('http://') ?
        '<a href="' + html(url) + '">' + title + '</a>' : title
    String body = '<div style="border-top:1px solid #ddd;padding:14px 0"><strong>' + link + '</strong><br>' +
        'Author: ' + html(pr['author']['name']) + ' | Team: ' + html(pr['author']['teamId'] ?: 'UNKNOWN') +
        ' | Owners: ' + html(pr['codeownerTeams'].join(', ')) + '<br>' +
        'Diff: +' + html(pr['diff']['added']) + ' / -' + html(pr['diff']['deleted']) +
        ' | Review points: ' + html(pr['reviewPoints']) + '<br>Approved: ' + html(pr['approvedBy'].join(', ') ?: 'none')
    if (pr['state'] == 'OPEN') {
        Map build = pr['build']
        Map merge = pr['merge']
        String conflicts = merge['conflicted'] == null ? 'UNKNOWN' : (merge['conflicted'] ? 'YES' : 'NO')
        String canMerge = merge['canMerge'] == null ? 'UNKNOWN' : (merge['canMerge'] ? 'YES' : 'NO')
        body += '<br>Build: ' + html(build['state']) + ' | Conflicts: ' + conflicts + ' | Can merge: ' + canMerge
        if (build['reason']) { body += '<br>' + html(build['reason']) }
        for (Map entry : build['entries']) { body += '<br>Build ' + html(entry['key']) + ': ' + html(entry['state']) }
        if (merge['reason']) { body += '<br>' + html(merge['reason']) }
        for (Map veto : merge['vetoes']) { body += '<br>Merge check: ' + html(veto['summaryMessage'] ?: veto['detailedMessage']) }
        body += '<br>Plan: ' + html(assignment['status']) +
            ' | Proposed: ' + html(assignment['proposed'].join(', ') ?: 'none') +
            ' | Existing commitments: ' + html(assignment['existing'].join(', ') ?: 'none')
        if (assignment['reason']) { body += '<br>' + html(assignment['reason']) }
        List people = []
        for (String id : (assignment['existing'] + assignment['selected'])) {
            if (!people.contains(id)) { people.add(id) }
        }
        for (String id : people) {
            body += renderReviewerActivity(pr, id, id == viewerId)
        }
    }
    return body + '</div>'
}

def displayDate(def time) {
    return time == null ? 'UNKNOWN' : new Date(time as Long).format('yyyy-MM-dd HH:mm:ss z')
}

def renderReviewerActivity(Map pr, String id, boolean isViewer) {
    Map activity = pr['reviewActivity'] ?: [:]
    Map state = activity['byMember']?.get(id) ?: [status: 'PENDING']
    String body = '<p><strong>' + (isViewer ? 'Your review' : 'Reviewer ' + html(id)) +
        ': ' + html(state['status']) + '</strong>'
    if (state['status'] == 'CHANGES_REQUESTED') {
        body += '<br>Changes requested at: ' + html(displayDate(state['changesRequestedAt']))
    } else if (state['status'] == 'APPROVED') {
        body += '<br>Approval date: ' + html(displayDate(state['approvedAt']))
    } else {
        if (state['changesRequestedAt'] != null) { body += '<br>Previously requested changes: ' + html(displayDate(state['changesRequestedAt'])) }
        if (state['approvedAt'] != null) { body += '<br>Previously approved: ' + html(displayDate(state['approvedAt'])) }
    }
    if (state['lastCommentAt'] != null) { body += '<br>Last comment: ' + html(displayDate(state['lastCommentAt'])) }
    if (state['lastReviewedCommit']) { body += '<br>Last reviewed revision: ' + html(state['lastReviewedCommit']) }
    body += '<br>Last observed source update: ' + html(displayDate(activity['lastSourceChangeAt']))
    if (state['needsAnotherLook'] == true) {
        String feedbackOwner = isViewer ? 'your' : 'this reviewer\'s'
        String description = activity['sourceChangeIncludesAddedCommits'] == true ?
            "New commits appeared after ${feedbackOwner} feedback — take another look." :
            "The source revision changed since ${feedbackOwner} feedback — take another look."
        body += '<br><strong>' + description + '</strong>'
    } else if (state['needsAnotherLook'] == false) {
        body += '<br>No newer source change detected after your last review activity.'
    } else {
        body += '<br>Whether changes arrived after your feedback: UNKNOWN.'
    }
    if (activity['historyStatus'] == 'UNKNOWN') { body += '<br>Activity history unavailable; revision comparison only.' }
    return body + '</p>'
}

def prLink(Map pr, String label) {
    String url = pr['url'] ?: ''
    return url.startsWith('https://') || url.startsWith('http://') ?
        '<a href="' + html(url) + '">' + html(label) + '</a>' : html(label)
}

def assignedPeople(Map assignment) {
    List ids = []
    for (String id : ((assignment['existing'] ?: []) + (assignment['selected'] ?: []))) {
        if (!ids.contains(id)) { ids.add(id) }
    }
    return ids
}

def reviewerCell(Map pr, List ids, Map plan, boolean includeOthers) {
    Map rows = [:]
    for (Map detail : (pr['reviewerDetails'] ?: [])) { rows[detail['id']] = detail }
    List people = []
    people.addAll(ids)
    if (includeOthers) {
        for (String id : rows.keySet()) { if (!people.contains(id)) { people.add(id) } }
        for (String id : pr['approvedBy']) { if (!people.contains(id)) { people.add(id) } }
    }
    List labels = []
    for (String id : people) {
        Map state = pr['reviewActivity']?.get('byMember')?.get(id) ?: [:]
        String status = rows[id]?.get('status') ?: state['status'] ?: (pr['approvedBy'].contains(id) ? 'APPROVED' : 'PENDING')
        status = status == 'CHANGES_REQUESTED' ? 'NEEDS_WORK' : (status == 'PENDING' ? 'UNAPPROVED' : status)
        String name = rows[id]?.get('name') ?: plan['members'][id]?.get('displayName') ?: id
        String label = html(name) + ' &rarr; <strong>' + html(status) + '</strong>'
        if (state['needsAnotherLook'] == true) { label += '<br><span style="color:#a15c00">New changes since feedback</span>' }
        labels.add(label)
    }
    return labels.join('<br>') ?: '&mdash;'
}

def tableCell(String content) {
    return '<td style="padding:8px;border-bottom:1px solid #e5e9f0;vertical-align:top">' + content + '</td>'
}

def compactTable(List headings, String rows) {
    String header = ''
    for (String heading : headings) { header += '<th align="left" style="padding:8px;background:#eef2f8">' + html(heading) + '</th>' }
    return '<table width="100%" cellspacing="0" cellpadding="0" style="border-collapse:collapse;font-size:12px"><thead><tr>' + header + '</tr></thead><tbody>' +
        (rows ?: '<tr><td colspan="' + headings.size() + '" style="padding:8px;color:#697386">None</td></tr>') + '</tbody></table>'
}

def reviewStartedAt(Map pr) {
    Long start = null
    for (Map event : (pr['reviewActivity']?.get('actions') ?: [])) {
        if ((event['action'] == 'RESCOPED' && event['fromHash'] == pr['sourceCommit'] &&
            event['previousFromHash'] && event['previousFromHash'] != event['fromHash']) ||
            (event['action'] == 'UPDATED' && event['draft'] == false)) {
            start = laterTimestamp(start, event['at'])
        }
    }
    return start
}

def notificationDecision(String id, Map plan, Map previous) {
    Map current = [assigned: [:], authored: [:], reminders: [:]]
    List reasons = []
    List newKeys = []
    Map assignments = [:]
    for (Map item : plan['assignments']) { assignments[item['prKey']] = item }
    Map oldest = null
    Long oldestStart = null
    for (Map pr : plan['openPullRequests']) {
        String key = pr['key']
        if (pr['author']['id'] == id) {
            Map statuses = [:]
            for (Map row : (pr['reviewerDetails'] ?: [])) { statuses[row['id']] = row['status'] }
            current['authored'][key] = statuses
            if (previous['authored']?.containsKey(key) && previous['authored'][key] != statuses) {
                reasons.add('REVIEW_CHANGED:' + key)
            }
        }
        if (pr['author']['id'] != id && assignedPeople(assignments[key]).contains(id)) {
            current['assigned'][key] = true
            if (!previous['assigned']?.containsKey(key)) { newKeys.add(key); reasons.add('NEW_ASSIGNMENT:' + key) }
            Long start = reviewStartedAt(pr)
            Map review = pr['reviewActivity']?.get('byMember')?.get(id) ?: [:]
            boolean waiting = !(review['status'] in ['APPROVED', 'CHANGES_REQUESTED']) && !pr['approvedBy'].contains(id)
            if (review['needsAnotherLook'] == true) { waiting = true }
            if (start != null && waiting) {
                String cycle = key + ':' + start
                current['reminders'][cycle] = previous['reminders']?.get(cycle) ?: 0
                if (oldestStart == null || start < oldestStart || (start == oldestStart && key.compareTo(oldest['key']) < 0)) {
                    oldest = pr; oldestStart = start
                }
            }
        }
    }
    if (oldest != null) {
        long elapsed = (plan['collectedAt'] as Long) - oldestStart
        int threshold = elapsed > 43200000L ? 12 : (elapsed > 28800000L ? 8 : (elapsed > 14400000L ? 4 : 0))
        String cycle = oldest['key'] + ':' + oldestStart
        if (threshold > (current['reminders'][cycle] as Integer)) {
            reasons.add('REVIEW_OVERDUE:' + oldest['key'] + ':' + threshold + 'h')
            current['reminders'][cycle] = threshold
        }
    }
    return [send: !reasons.isEmpty(), reasons: reasons, newKeys: newKeys, nextState: current]
}

def buildIcon(Map pr, boolean showConflicts) {
    String status = pr['build']?.get('state') ?: 'UNKNOWN'
    String symbol = status == 'SUCCESSFUL' ? '&#10004;' : (status in ['FAILED', 'CANCELLED'] ? '&#10008;' : (status == 'INPROGRESS' ? '&#9203;' : '&#8212;'))
    String color = status == 'SUCCESSFUL' ? '#16803c' : (status in ['FAILED', 'CANCELLED'] ? '#c62828' : '#697386')
    String result = '<span title="' + html(status) + '" aria-label="' + html(status) + '" style="font-size:18px;font-weight:bold;color:' + color + '">' + symbol + '</span>'
    if (showConflicts && pr['merge']?.get('conflicted') == true) {
        result += ' <span title="Merge conflicts" aria-label="Merge conflicts" style="font-size:18px;color:#d89b00">&#9888;</span>'
    }
    return result
}

def ageCell(Map pr, long now) {
    long elapsed = Math.max(0L, now - (pr['createdDate'] as Long))
    long minutes = elapsed.intdiv(60000L)
    String label = minutes < 60 ? "${minutes}m" : (minutes < 1440 ? "${minutes.intdiv(60)}h" : "${minutes.intdiv(1440)}d")
    String style = elapsed > 28800000L ? 'background:#fee2e2;color:#b42318' : (elapsed > 14400000L ? 'background:#fff2b3;color:#805500' : 'color:#172b4d')
    return '<span style="padding:3px 5px;border-radius:4px;' + style + '">' + label + '</span>'
}

def renderDigest(String memberId, Map plan) {
    Map member = plan['members'][memberId]
    Map assignments = [:]
    for (Map item : plan['assignments']) { assignments[item['prKey']] = item }
    Map sections = [created: [], assigned: [], team: [], other: []]
    for (Map pr : plan['openPullRequests']) {
        List people = assignedPeople(assignments[pr['key']])
        boolean teammate = false
        for (String id : people) { if (id != memberId && plan['members'][id]?.get('teamId') == member['teamId']) { teammate = true } }
        if (pr['author']['id'] == memberId) { sections['created'].add(pr) }
        else if (people.contains(memberId)) { sections['assigned'].add(pr) }
        else if (teammate) { sections['team'].add(pr) }
        else if (pr['codeownerTeams'].contains(member['teamId'])) { sections['other'].add(pr) }
    }
    Map labels = [created: 'PRs created by you', assigned: 'PRs assigned to you',
        team: 'PRs of team members', other: 'Other PRs in team repositories']
    String body = ''
    for (String section : labels.keySet()) {
        List headings = ['Repository', 'PR title']
        if (section != 'created') { headings.add('Author') }
        headings.add('Age')
        if (section != 'other') { headings.add('Build') }
        if (section != 'other') { headings.add('Diff') }
        headings.add(section == 'assigned' ? 'Your review' : 'Reviewers')
        String rows = ''
        for (Map pr : sections[section]) {
            Map assignment = assignments[pr['key']]
            String title = html(pr['title'])
            if (assignment['persisted'] != true && assignment['proposed'].contains(memberId) && section == 'assigned') { title += '<br><small style="color:#697386">Proposed assignment</small>' }
            String marker = (plan['notifications']?.get(memberId)?.get('newKeys') ?: []).contains(pr['key']) ? '<strong>[NEW]</strong> ' : ''
            rows += '<tr>' + tableCell(marker + prLink(pr, '↗ ' + (pr['repositoryName'] ?: pr['repositoryId']))) + tableCell(title)
            if (section != 'created') { rows += tableCell(html(pr['author']['name'])) }
            rows += tableCell(ageCell(pr, plan['collectedAt'] as Long))
            if (section != 'other') {
                rows += tableCell(buildIcon(pr, section in ['created', 'assigned']))
                rows += tableCell('+' + html(pr['diff']['added']) + '/-' + html(pr['diff']['deleted']))
            }
            rows += tableCell(reviewerCell(pr, section == 'assigned' ? [memberId] : assignedPeople(assignment), plan, section in ['created', 'other'])) + '</tr>'
        }
        body += '<h2 style="font-size:16px;margin:24px 0 8px">' + labels[section] + '</h2>' + compactTable(headings, rows)
    }
    Map groups = [:]
    for (Map pr : plan['mergedPullRequests']) {
        String key = pr['repositoryId'] + ':' + (pr['projectVersion']?.get('value') ?: 'UNKNOWN')
        if (!groups.containsKey(key)) { groups[key] = [] }
        groups[key].add(pr)
    }
    String rows = ''
    for (List group : groups.values()) {
        boolean first = true
        for (Map pr : group) {
            rows += '<tr>'
            if (first) {
                rows += '<td rowspan="' + group.size() + '" style="padding:8px;border-bottom:1px solid #e5e9f0;vertical-align:top">' + html(pr['repositoryName'] ?: pr['repositoryId']) + '</td>'
                rows += '<td rowspan="' + group.size() + '" style="padding:8px;border-bottom:1px solid #e5e9f0;vertical-align:top">' + html(pr['projectVersion']?.get('value') ?: 'UNKNOWN') + '</td>'
            }
            rows += tableCell(prLink(pr, '↗ ' + pr['title'])) + tableCell(html(pr['author']['name'])) + '</tr>'
            first = false
        }
    }
    body += '<h2 style="font-size:16px;margin:24px 0 8px">Recently merged</h2>' + compactTable(['Repository', 'Version', 'PR title', 'Author'], rows)
    String template = readFile(file: 'pr-checker/email-template.html', encoding: 'UTF-8')
    if (plan['mode'] == 'ASSIGNMENTS_PERSISTED') { template = template.replace('Proposed assignments have not been saved.', 'Assignments saved in Bitbucket; unresolved PRs require manual attention.') }
    // Substitute body last: user text can never inject a template placeholder.
    return template.replace('{{TITLE}}', html('PR review — ' + (member['displayName'] ?: member['lanId'])))
        .replace('{{SCORE}}', html(plan['scores'][memberId]['score']))
        .replace('{{BONUS}}', html(plan['scores'][memberId]['bonusPoints']))
        .replace('{{BODY}}', body)
}

def writeReports(Map config, Map plan) {
    List manifest = []
    String index = '<h2>Global scores</h2><table border="1" cellpadding="6"><tr>' +
        '<th>LAN ID</th><th>Score</th><th>Reviews</th><th>Size points</th><th>Bonus</th><th>Open points</th><th>Proposed</th><th>Effective</th></tr>'
    int number = 0
    for (String id : plan['members'].keySet()) {
        number++
        String filename = "member-${number}.html"
        writeFile(file: "pr-checker-output/${filename}", text: renderDigest(id, plan), encoding: 'UTF-8')
        manifest.add([memberId: id, to: plan['members'][id]['email'], subject: 'PR review', file: filename, notification: plan['notifications']?.get(id)])
        Map score = plan['scores'][id]
        index += '<tr><td><a href="' + filename + '">' + html(id) + '</a></td>'
        for (String key : ['score', 'reviews', 'sizePoints', 'bonusPoints', 'openPoints', 'proposedPoints', 'effectiveScore']) {
            index += '<td>' + html(score[key]) + '</td>'
        }
        index += '</tr>'
    }
    index += '</table><h2>Open pull requests and assignment plan</h2>'
    Map assignments = [:]
    for (Map item : plan['assignments']) { assignments[item['prKey']] = item }
    for (Map pr : plan['openPullRequests']) { index += renderPr(pr, assignments[pr['key']]) }
    writeFile(file: 'pr-checker-output/index.html', text: reportPage('Review orchestrator', index), encoding: 'UTF-8')
    writeJSON(file: 'pr-checker-output/email-previews.json', json: manifest, pretty: 2)
}

// State directory must be a persistent, job-specific volume. Never clean it with output.
def prepareNotifications(Map config, Map plan) {
    Map state = [schemaVersion: 1, members: [:]]
    dir(config['notifications']['stateDirectory']) {
        if (fileExists('state.json')) {
            state = readJSON(file: 'state.json', returnPojo: true)
            if (state['schemaVersion'] != 1 || !(state['members'] instanceof Map)) { error('Invalid notification state; refusing to reset deduplication') }
        }
    }
    plan['notifications'] = [:]
    for (String id : plan['members'].keySet()) {
        plan['notifications'][id] = notificationDecision(id, plan, state['members'][id] ?: [:])
    }
    return state
}

def deliverNotifications(Map config, Map plan, Map state) {
    if (config['notifications']['previewOnly']) { return }
    int number = 0
    for (String id : plan['members'].keySet()) {
        number++
        Map decision = plan['notifications'][id]
        if (decision['send']) {
            String body = readFile(file: "pr-checker-output/member-${number}.html", encoding: 'UTF-8')
            body = body.replace('PREVIEW ONLY · ', 'Review digest · ').replace(' No email sent.', '')
            emailext(to: plan['members'][id]['email'], subject: 'PR review', mimeType: 'text/html', body: body)
        }
        // Commit only after the email step returns successfully, separately for each person.
        state['members'][id] = decision['nextState']
        dir(config['notifications']['stateDirectory']) {
            writeJSON(file: 'state.next.json', json: state, pretty: 2)
            sh(script: 'mv -f state.next.json state.json', label: 'Save notification state')
        }
    }
}

return this


