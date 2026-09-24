import org.yaml.snakeyaml.Yaml
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

def shell = new GroovyShell()
def interruptionClass = shell.classLoader.parseClass(new File('tests/FlowInterruptedException.groovy'))
def module = shell.parse(new File('pr-checker/Orchestrator.groovy'))
assert module.run() == module
module.binding.setVariable('env', [GIT_USER:'jenkins-bot'])
module.metaClass.readFile = { Map args -> new File(args.file).getText('UTF-8') }
def logs = []
module.metaClass.echo = { Object text -> logs.add(text.toString()) }
module.metaClass.error = { Object text -> throw new IllegalStateException(text.toString()) }
def reject = { Closure action ->
    boolean failed = false
    try { action() } catch (IllegalStateException e) { failed = true }
    assert failed
}
def original = new Yaml().load(new File('pr-checker.yaml').text)
def copy = { value -> new JsonSlurper().parseText(JsonOutput.toJson(value)) }
def config = module.validateConfiguration(copy(original))
assert config.members.size() == 6
assert config.policy.largePrThreshold == 300
for (int n : [0,1,7,25]) {
    def cfg = copy(original)
    cfg.teams[0].members = (0..<n).collect { [lanId:('other'+it),email:('other'+it+'@test.com')] }
    assert module.validateConfiguration(cfg).members.size() == n+3
}
reject { module.validateConfiguration([schemaVersion:1]) }
def bad = copy(original); bad.repositories[0].codeownerTeams = ['missing']
reject { module.validateConfiguration(bad) }
bad = copy(original); bad.teams[1].members.add(bad.teams[0].members[0])
reject { module.validateConfiguration(bad) }
bad = copy(original); bad.repositories[1].slug = bad.repositories[0].slug
reject { module.validateConfiguration(bad) }
bad = copy(original); bad.repositories[0].id = '../outside'
reject { module.validateConfiguration(bad) }
assert module.findTeamMember([emailAddress:'DEVELOPER1@company.com'],config.members).teamId == 'team-a'
reject { module.findTeamMember([name:'LAN001',emailAddress:'developer2@company.com'],config.members) }

// Branch glob matching is exact, case-sensitive, literal except * and ?.
assert module.matchesTargetBranch([toRef:[id:'refs/heads/develop']],config.targetBranchPatterns)
assert module.matchesTargetBranch([toRef:[displayId:'release-candidate']],config.targetBranchPatterns)
assert !module.matchesTargetBranch([toRef:[id:'refs/heads/feature/develop']],config.targetBranchPatterns)
assert !module.matchesTargetBranch([toRef:[id:'refs/heads/Develop']],config.targetBranchPatterns)
assert module.matchesTargetBranch([toRef:[id:'refs/heads/release/1.2']],['release/*'])
assert module.matchesTargetBranch([toRef:[id:'refs/heads/release/1.2']],['release/?.2'])
assert !module.matchesTargetBranch([toRef:[id:'refs/heads/release/1x2']],['release/1.2'])
assert !module.matchesTargetBranch([toRef:[id:'refs/heads/main',displayId:'develop']],config.targetBranchPatterns)
assert !module.matchesTargetBranch([:],config.targetBranchPatterns)
assert !module.matchesTargetBranch([toRef:[id:'refs/heads/develop']],[])
bad = copy(original); bad.targetBranchPatterns = 'develop'; reject { module.validateConfiguration(bad) }
bad = copy(original); bad.repositories[0].targetBranchPatterns = null; reject { module.validateConfiguration(bad) }
// Filter before any expensive per-PR collection; overrides replace the global list.
def filterModule = shell.parse(new File('pr-checker/Orchestrator.groovy'))
filterModule.metaClass.echo = { Object text -> }
filterModule.metaClass.fetchOpenPullRequests = { String api ->
    [[id:1,toRef:[id:'refs/heads/develop']],[id:2,toRef:[id:'refs/heads/main']]]
}
filterModule.metaClass.fetchRecentMergedPullRequests = { String api, int size, long time, int days ->
    [[id:3,toRef:[id:'refs/heads/release-candidate']],[id:4,toRef:[id:'refs/heads/main']]]
}
def collectedIds = []
filterModule.metaClass.collectPullRequest = { Map cfg, Map repo, Map pr, String api -> collectedIds.add(pr.id); pr }
def filtered = filterModule.collectRepository(config,config.repositories[0],123L)
assert collectedIds == [1,3]
assert filtered.openPullRequests*.id == [1] && filtered.mergedPullRequests*.id == [3]
collectedIds.clear()
def overrideRepo = copy(config.repositories[0]); overrideRepo.targetBranchPatterns = ['main']
filterModule.collectRepository(config,overrideRepo,123L)
assert collectedIds == [2,4]
collectedIds.clear(); overrideRepo.targetBranchPatterns = []
filterModule.collectRepository(config,overrideRepo,123L)
assert collectedIds.isEmpty()

// Overlapping size criteria select exactly one highest award, regardless of metric.
assert module.calculateSizeAward(config.scoring,299L,9L) == [points:30L,ruleId:'lines-medium']
assert module.calculateSizeAward(config.scoring,300L,9L) == [points:60L,ruleId:'lines-large']
assert module.calculateSizeAward(config.scoring,300L,10L) == [points:80L,ruleId:'files-many']
assert module.calculateSizeAward(config.scoring,0L,0L).points == 10
assert module.calculateSizeAward([sizeRules:[]],300L,null).points == 0
assert module.calculateSizeAward([sizeRules:[[id:'x',metric:'changedLines',min:100,max:200,points:5]]],99L,null).points == 0
reject { module.calculateSizeAward(config.scoring,300L,null) }
def tiedRules = [sizeRules:[[id:'a',metric:'changedLines',min:0,points:10],[id:'b',metric:'changedFiles',min:0,points:10]]]
assert module.calculateSizeAward(tiedRules,100L,20L).ruleId == 'a'
bad = copy(original); bad.scoring.sizeRules[0].points = -1; reject { module.validateConfiguration(bad) }
bad = copy(original); bad.scoring.sizeRules[0].max = -1; reject { module.validateConfiguration(bad) }
bad = copy(original); bad.scoring.sizeRules[0].metric = 'typo'; reject { module.validateConfiguration(bad) }
bad = copy(original); bad.scoring.sizeRules[0].min = 100; reject { module.validateConfiguration(bad) }

long now = 2000000000000L
long cutoff = now - 14L*24*60*60*1000
int calls = 0
module.metaClass.executeBitbucketGet = { String url ->
    calls++
    assert url.contains('order=CLOSED_DATE') && url.contains('limit=30')
    if (calls <= 3) {
        assert url.contains("start=${(calls-1)*30}")
        return [values:(1..30).collect { [id:(calls-1)*30+it,state:'MERGED',closedDate:now-calls*30-it] },
            isLastPage:false,nextPageStart:calls*30]
    }
    [values:[[id:91,state:'MERGED',closedDate:cutoff],[id:92,state:'MERGED',closedDate:cutoff-1]],isLastPage:false]
}
assert module.fetchRecentMergedPullRequests('https://test',30,now,14).size() == 91
assert calls == 4
module.metaClass.executeBitbucketGet = { String url -> [values:[],isLastPage:false,nextPageStart:0] }
reject { module.fetchRecentMergedPullRequests('https://test',30,now,14) }
module.metaClass.executeBitbucketGet = { String url -> [values:[],isLastPage:true] }
assert module.fetchRecentMergedPullRequests('https://test',30,now,14) == []
module.metaClass.executeBitbucketGet = { String url ->
    url.contains('start=0') ? [values:[[id:1,state:'OPEN',draft:true],[id:2,state:'OPEN']],isLastPage:false,nextPageStart:7] :
        [values:[[id:3,state:'OPEN']],isLastPage:true]
}
assert module.fetchOpenPullRequests('https://test')*.id == [2,3]

assert module.summarizeBuilds([]).state == 'NO_STATUS'
assert module.summarizeBuilds([[key:'ci',state:'FAILED',updatedDate:1],[key:'ci',state:'SUCCESSFUL',updatedDate:2]]).state == 'SUCCESSFUL'
assert module.summarizeBuilds([[key:'ci',state:'SUCCESSFUL'],[key:'test',state:'FAILED']]).state == 'FAILED'
assert module.summarizeBuilds([[key:'ci',state:'INPROGRESS']]).state == 'INPROGRESS'
assert module.summarizeBuilds([[key:'ci',state:'CANCELLED']]).state == 'CANCELLED'
assert module.summarizeBuilds([[key:'ci',state:'MYSTERY']]).state == 'UNKNOWN'
module.metaClass.executeBitbucketGet = { String url -> [conflicted:false,canMerge:false,vetoes:[[summaryMessage:'Need approval']]] }
assert module.collectMergeStatus('https://test').conflicted == false
assert module.collectMergeStatus('https://test').canMerge == false
module.metaClass.executeBitbucketGet = { String url -> throw new IllegalStateException('Unavailable') }
assert module.collectMergeStatus('https://test').conflicted == null
def raw = [id:1,title:'Example <script>alert(1)</script>',state:'OPEN',draft:false,version:1,
    createdDate:now-1000,author:[user:[name:'LAN001']],reviewers:[[user:[name:'LAN002'],approved:true]],
    fromRef:[latestCommit:'abcdef',repository:[slug:'fork',project:[key:'FORK']]],toRef:[latestCommit:'123456']]
assert module.collectBuildStatus(config,raw).state == 'UNKNOWN'
module.metaClass.executeBitbucketGet = { String url -> throw interruptionClass.newInstance() }
boolean interrupted = false
try { module.collectMergeStatus('https://test') } catch (Exception e) { interrupted = interruptionClass.isInstance(e) }
assert interrupted
def requested = []
module.metaClass.executeBitbucketGet = { String url ->
    requested.add(url)
    if (url.contains('/activities?')) return [values:[],isLastPage:true]
    if (url.endsWith('/diff-stats-summary/')) return [addedLines:180,deletedLines:120,modifiedFiles:4]
    if (url.contains('/builds?')) {
        assert url.contains('/projects/FORK/repos/fork/commits/abcdef/builds')
        return [values:[[key:'ci',state:'SUCCESSFUL']],isLastPage:true]
    }
    if (url.endsWith('/merge')) return [canMerge:false,conflicted:false,vetoes:[]]
    return raw
}
def collected = module.collectPullRequest(config,config.repositories[0],raw,'https://test')
assert collected.author.id == 'lan001' && collected.author.teamId == 'team-a'
assert collected.diff.changed == 300 && collected.reviewPoints == 110
assert collected.build.state == 'SUCCESSFUL' && collected.assignment.status == 'NONE'
assert collected.approvedBy == ['lan002']
// Regression: exact diff summary returned by the user's Bitbucket instance.
module.metaClass.executeBitbucketGet = { String url ->
    if (url.endsWith('/diff-stats-summary/')) return [filesChanged:10,totalDeletions:9,totalInsertions:246]
    if (url.contains('/activities?') || url.contains('/builds?')) return [values:[],isLastPage:true]
    if (url.endsWith('/merge')) return [canMerge:false,conflicted:false,vetoes:[]]
    return raw
}
def actualSummaryPr = module.collectPullRequest(config,config.repositories[0],raw,'https://test')
assert actualSummaryPr.diff.added == 246 && actualSummaryPr.diff.deleted == 9
assert actualSummaryPr.diff.changed == 255 && actualSummaryPr.diff.changedFiles == 10
assert actualSummaryPr.diff.sizeRule == 'files-many' && actualSummaryPr.diff.sizePoints == 80
assert actualSummaryPr.reviewPoints == 130 // base 50 + highest matching size rule 80
module.metaClass.executeBitbucketGet = { String url ->
    if (url.endsWith('/diff-stats-summary/')) return [filesChanged:0,totalDeletions:0,totalInsertions:0]
    if (url.contains('/activities?') || url.contains('/builds?')) return [values:[],isLastPage:true]
    if (url.endsWith('/merge')) return [canMerge:false,conflicted:false,vetoes:[]]
    return raw
}
assert module.collectPullRequest(config,config.repositories[0],raw,'https://test').diff.changed == 0
// Review state, published feedback chronology and source-only rescope events.
def reviewedPr = copy(raw)
reviewedPr.reviewers = [[user:[name:'LAN002'],status:'NEEDS_WORK',approved:false,lastReviewedCommit:'old-head']]
def feedback = [
    [action:'REVIEWED',user:[name:'LAN002'],createdDate:100L],
    [action:'COMMENTED',commentAction:'ADDED',user:[name:'LAN002'],createdDate:110L,
        comment:[author:[name:'LAN002'],createdDate:110L,comments:[]]],
    [action:'RESCOPED',fromHash:'abcdef',previousFromHash:'old-head',createdDate:200L,added:[total:1]]
]
def activity = module.summarizeReviewActivity(reviewedPr,config.members,feedback,'AVAILABLE')
assert activity.byMember.lan002.status == 'CHANGES_REQUESTED'
assert activity.byMember.lan002.changesRequestedAt == 100L
assert activity.byMember.lan002.lastCommentAt == 110L
assert activity.byMember.lan002.needsAnotherLook == true
assert activity.lastSourceChangeAt == 200L
assert module.teamReviewers(reviewedPr,config.members)[0].approved == false
def laterComment = [action:'COMMENTED',commentAction:'ADDED',user:[name:'LAN002'],createdDate:250L]
assert module.summarizeReviewActivity(reviewedPr,config.members,feedback+[laterComment],'AVAILABLE').byMember.lan002.needsAnotherLook == false
def deletedComment = laterComment + [commentAction:'DELETED']
assert module.summarizeReviewActivity(reviewedPr,config.members,feedback+[deletedComment],'AVAILABLE').byMember.lan002.needsAnotherLook == true
def targetOnly = [action:'RESCOPED',fromHash:'abcdef',previousFromHash:'abcdef',createdDate:300L,added:[total:4]]
assert module.summarizeReviewActivity(reviewedPr,config.members,feedback+[targetOnly],'AVAILABLE').lastSourceChangeAt == 200L
def noHistory = module.summarizeReviewActivity(reviewedPr,config.members,[],'UNKNOWN')
assert noHistory.byMember.lan002.needsAnotherLook == true
assert noHistory.byMember.lan002.changesRequestedAt == null
def currentReview = copy(reviewedPr); currentReview.reviewers[0].lastReviewedCommit = 'abcdef'
assert module.summarizeReviewActivity(currentReview,config.members,[],'AVAILABLE').byMember.lan002.needsAnotherLook == false
currentReview.reviewers[0].status = 'APPROVED'
assert module.summarizeReviewActivity(currentReview,config.members,feedback,'AVAILABLE').byMember.lan002.status == 'APPROVED'
def commentOnly = copy(raw); commentOnly.reviewers = []
def commentState = module.summarizeReviewActivity(commentOnly,config.members,feedback,'AVAILABLE').byMember.lan002
assert commentState.status == 'PENDING' && commentState.needsAnotherLook == true
def reply = [action:'COMMENTED',commentAction:'ADDED',user:[name:'LAN003'],createdDate:50L,
    comment:[author:[name:'LAN003'],createdDate:50L,comments:[[author:[name:'LAN002'],createdDate:260L,comments:[]]]]]
assert module.summarizeReviewActivity(reviewedPr,config.members,feedback+[reply],'AVAILABLE').byMember.lan002.lastCommentAt == 260L
assert module.summarizeReviewActivity(commentOnly,config.members,[],'AVAILABLE').byMember.lan002.needsAnotherLook == null
collected.reviewActivity = activity
assert module.renderReviewerActivity(collected,'lan002',true).contains('Your review: CHANGES_REQUESTED')
assert module.renderReviewerActivity(collected,'lan002',true).contains('New commits appeared after your feedback')
module.metaClass.executeBitbucketGet = { String url ->
    if (url.endsWith('/diff-stats-summary/')) return [addedLines:1,deletedLines:0,modifiedFiles:1]
    if (url.contains('/builds?')) return [values:[],isLastPage:true]
    if (url.endsWith('/merge')) return [:]
    return raw + [version:2]
}
reject { module.collectPullRequest(config,config.repositories[0],raw,'https://test') }

def makePr = { String repoId, int id, String authorId, String owner, int changed, List approvals, String state ->
    def member = config.members.find { it.lanId.toLowerCase() == authorId }
    [type:'PullRequest',key:(repoId+'#'+id),id:id,repositoryId:repoId,title:'Change '+id,state:state,
        createdDate:now-10000+id,closedDate:state=='MERGED'?now-id:null,url:'https://test/pr/'+id,
        author:[id:authorId,name:authorId,teamId:member?.teamId],codeownerTeams:[owner],
        reviewers:[],approvedBy:approvals,diff:[added:changed,deleted:0,changed:changed,sizePoints:changed],reviewPoints:50L+changed,
        assignment:[status:'NOT_IMPLEMENTED',reviewers:[]],build:[state:'NO_STATUS',observed:false,entries:[],reason:'No published status'],
        merge:[canMerge:false,conflicted:false,vetoes:[[summaryMessage:'Approvals required']]]]
}
def snap = { String repoId, List open, List merged ->
    [type:'RepositorySnapshot',schemaVersion:1,collectedAt:now,repositoryId:repoId,openPullRequests:open,mergedPullRequests:merged]
}
def ownSmall = makePr('service-a',1,'lan001','team-a',10,[],'OPEN')
def ownLarge = makePr('service-a',2,'lan001','team-a',300,[],'OPEN')
def externalSmall = makePr('service-b',3,'lan001','team-b',299,[],'OPEN')
def externalLarge = makePr('service-b',4,'lan001','team-b',300,[],'OPEN')
assert module.requiredTeams(ownSmall,config.policy) == [['team-a']]
assert module.requiredTeams(ownLarge,config.policy) == [['team-a'],['team-a']]
assert module.requiredTeams(externalSmall,config.policy) == [['team-a']]
assert module.requiredTeams(externalLarge,config.policy) == [['team-a'],['team-b']]
def multiOwner = copy(externalLarge)
multiOwner.codeownerTeams = ['team-b','team-c']
assert module.requiredTeams(multiOwner,config.policy) == [['team-a'],['team-b','team-c']]
def memberOfSecondOwner = copy(ownLarge)
memberOfSecondOwner.codeownerTeams = ['team-b','team-a']
assert module.requiredTeams(memberOfSecondOwner,config.policy) == [['team-a'],['team-a']]
def mergedA = makePr('service-a',7,'lan001','team-a',20,['lan002'],'MERGED')
def mergedB = makePr('service-b',7,'lan004','team-b',30,['lan002'],'MERGED')
def snapshots = [snap('service-a',[ownSmall,ownLarge],[mergedA]),snap('service-b',[externalSmall,externalLarge],[mergedB])]
def plan = module.aggregateAndPlan(config,snapshots,now)
assert plan.scores.lan002.score == 150 && plan.scores.lan002.reviews == 2
assert plan.scores.lan002.sizePoints == 50
assert plan.assignments[0].proposed == ['lan003'] // score includes both repositories
assert plan.assignments[1].proposed.toSet() == ['lan002','lan003'].toSet()
assert plan.assignments[3].proposed.size() == 2 && plan.assignments[3].proposed.contains('lan004')
assert plan.assignments.every { !it.proposed.contains('lan001') }
def threeTeams = copy(config)
threeTeams.members.add([lanId:'LAN007',email:'developer7@company.com',teamId:'team-c'])
def busyOwner = makePr('service-b',55,'lan001','team-b',999,['lan004','lan005','lan006'],'MERGED')
def multiOwnerPlan = module.aggregateAndPlan(threeTeams,
    [snap('service-a',[],[]),snap('service-b',[multiOwner],[busyOwner])],now)
assert multiOwnerPlan.assignments[0].proposed == ['lan002','lan007']
assert module.aggregateAndPlan(config,snapshots.reverse(),now).assignments == plan.assignments
reject { module.aggregateAndPlan(config,[snapshots[0]],now) }
reject { module.aggregateAndPlan(config,[snapshots[0],snapshots[0]],now) }
// Early proposal must account for a later PR's existing open approval.
def later = makePr('service-b',99,'lan004','team-b',1000,['lan002'],'OPEN')
def reserved = module.aggregateAndPlan(config,[snap('service-a',[ownSmall],[]),snap('service-b',[later],[])],now)
assert reserved.assignments[0].proposed == ['lan003'] && reserved.scores.lan002.openPoints == 1050
// Partial multi-role choices must not reserve any points when owner team is empty.
def emptyOwnerConfig = copy(config)
emptyOwnerConfig.members = emptyOwnerConfig.members.findAll { it.teamId != 'team-b' }
def manual = module.aggregateAndPlan(emptyOwnerConfig,[snap('service-a',[],[]),snap('service-b',[externalLarge],[])],now)
assert manual.assignments[0].status == 'MANUAL'
assert manual.scores.values().every { it.proposedPoints == 0 }
def unknown = makePr('service-a',5,'outsider','team-a',500,[],'OPEN')
def unknownPlan = module.aggregateAndPlan(config,[snap('service-a',[unknown],[]),snap('service-b',[],[])],now)
assert unknownPlan.assignments[0].status == 'MANUAL'
def covered = copy(ownSmall); covered.approvedBy = ['lan002']
def coveredPlan = module.aggregateAndPlan(config,[snap('service-a',[covered],[]),snap('service-b',[],[])],now)
assert coveredPlan.assignments[0].status == 'COVERED' && coveredPlan.assignments[0].proposed == []
def needsWorkPr = copy(ownSmall)
needsWorkPr.reviewActivity = activity
def needsWorkPlan = module.aggregateAndPlan(config,[snap('service-a',[needsWorkPr],[]),snap('service-b',[],[])],now)
assert needsWorkPlan.assignments[0].status == 'REVIEW_IN_PROGRESS'
assert needsWorkPlan.assignments[0].selected == ['lan002']
assert needsWorkPlan.assignments[0].proposed == []
assert needsWorkPlan.scores.lan002.score == 0 && needsWorkPlan.scores.lan002.reviews == 0
assert needsWorkPlan.scores.lan002.openPoints == 60
assert module.renderDigest('lan002',needsWorkPlan).contains('NEEDS_WORK')
def assignedPr = copy(ownSmall)
assignedPr.assignment = [status:'KNOWN',reviewers:['lan002']]
assignedPr.reviewActivity = activity
def assignedPlan = module.aggregateAndPlan(config,[snap('service-a',[assignedPr],[]),snap('service-b',[],[])],now)
assert assignedPlan.scores.lan002.openPoints == 60 // known assignment + needs-work counted once
def resetPr = copy(needsWorkPr)
resetPr.reviewActivity.byMember.lan002.status = 'PENDING'
def resetPlan = module.aggregateAndPlan(config,[snap('service-a',[resetPr],[]),snap('service-b',[],[])],now)
assert resetPlan.assignments[0].selected == ['lan002'] && resetPlan.assignments[0].proposed == []
// Native reviewer membership alone is deliberately not an assignment.
def nativePr = copy(ownSmall); nativePr.reviewers = ['lan002','lan003']
def nativePlan = module.aggregateAndPlan(config,[snap('service-a',[nativePr],[]),snap('service-b',[],[])],now)
assert nativePlan.assignments[0].status == 'PREVIEW'
assert nativePlan.scores.lan002.openPoints == 0
assert nativePlan.assignments[0].proposed.size() == 1
assert copy(plan).assignments == plan.assignments // JSON DTO round trip
String digest = module.renderDigest('lan001',plan)
assert digest.contains('PREVIEW ONLY') && digest.contains('PRs created by you') && digest.contains('Recently merged')
collected.title = '<script>alert(1)</script>'
assert !module.renderPr(collected,plan.assignments[0]).contains('<script>')
assert !module.renderPr(mergedA,[:]).contains('Plan:')
def files = [:]
module.metaClass.writeFile = { Map args -> files[args.file] = args.text }
module.metaClass.writeJSON = { Map args -> files[args.file] = JsonOutput.toJson(args.json) }
module.writeReports(config,plan)
assert files.keySet().findAll { it.endsWith('.html') }.size() == 7
assert new JsonSlurper().parseText(files['pr-checker-output/email-previews.json']).size() == 6
// Fast-review timing: inclusive boundaries, current source, reset and missing evidence.
def fast = copy(mergedA)
fast.sourceCommit = 'head'
fast.reviewActivity = [historyStatus:'AVAILABLE', byMember:[lan002:[lastReviewedCommit:'head']], actions:[
    [action:'RESCOPED',at:1000L,fromHash:'head',previousFromHash:'old'],
    [action:'APPROVED',at:10801000L,memberId:'lan002']]]
assert module.reviewAward(fast,'lan002',config).bonusPoints == 10
fast.reviewActivity.actions[1].at++
assert module.reviewAward(fast,'lan002',config).bonusPoints == 0
fast.diff.changed = 300
assert module.reviewAward(fast,'lan002',config).bonusPoints == 10
fast.reviewActivity.actions[1].at = 21601000L
assert module.reviewAward(fast,'lan002',config).bonusPoints == 10
fast.reviewActivity.actions[1].at++
assert module.reviewAward(fast,'lan002',config).bonusPoints == 0
fast.reviewActivity.actions.add([action:'UPDATED',draft:false,at:21600000L])
assert module.reviewAward(fast,'lan002',config).bonusPoints == 10
fast.reviewActivity.actions.add([action:'UNAPPROVED',memberId:'lan002',at:21601002L])
assert module.reviewAward(fast,'lan002',config).bonusPoints == 0
fast.reviewActivity.actions.remove(3)
fast.reviewActivity.byMember.lan002.lastReviewedCommit = 'old'
assert module.reviewAward(fast,'lan002',config).bonusPoints == 0
fast.reviewActivity.byMember.lan002.lastReviewedCommit = 'head'
def fastPlan = module.aggregateAndPlan(config,[snap('service-a',[],[fast]),snap('service-b',[],[])],now)
assert fastPlan.scores.lan002.score == 80 && fastPlan.scores.lan002.bonusPoints == 10
fast.reviewActivity.historyStatus = 'UNKNOWN'
assert module.reviewAward(fast,'lan002',config).bonusPoints == 0
fast.reviewActivity = [historyStatus:'AVAILABLE',actions:[[action:'UPDATED',at:1000L],[action:'APPROVED',at:2000L,memberId:'lan002']]]
assert module.reviewAward(fast,'lan002',config).reason == 'TIMING_UNKNOWN'
// Version is read from the source repository at an immutable source hash.
def versionUrl = null
module.metaClass.executeBitbucketTextGet = { String url -> versionUrl = url; 'version=1.2.3\nother=x' }
module.metaClass.readProperties = { Map args ->
    assert args.interpolate == false
    def props = new Properties(); props.load(new StringReader(args.text)); props
}
def versionRaw = [id:7,fromRef:[latestCommit:'f00abc',repository:[project:[key:'FORK'],slug:'fork-service']]]
assert module.collectProjectVersion(config,versionRaw).value == '1.2.3'
assert versionUrl.endsWith('/projects/FORK/repos/fork-service/raw/gradle.properties?at=f00abc')
module.metaClass.executeBitbucketTextGet = { String url -> 'other=x' }
assert module.collectProjectVersion(config,versionRaw).status == 'MISSING_VERSION'
module.metaClass.executeBitbucketTextGet = { String url -> throw new IOException('404') }
assert module.collectProjectVersion(config,versionRaw).status == 'UNAVAILABLE'
// Group equal versions within one repository, never across repositories.
mergedA.projectVersion = [value:'1.2.3']
mergedB.projectVersion = [value:'1.2.3']
def mergedA2 = copy(mergedA); mergedA2.key='service-a#8'; mergedA2.id=8; mergedA2.title='Second change'
def groupedPlan = module.aggregateAndPlan(config,[snap('service-a',[],[mergedA,mergedA2]),snap('service-b',[],[mergedB])],now)
assert module.renderDigest('lan002',groupedPlan).count('rowspan="2"') == 2
// Created-by-recipient takes precedence over assignments; a PR appears only once.
def ownDigest = module.renderDigest('lan001',plan)
assert ownDigest.count('Change 1</td>') == 1
assert ownDigest.count('>Diff</th>') == 3
assert ownDigest.contains('+10/-0')
assert ownDigest.indexOf('>Diff</th>') < ownDigest.indexOf('>Reviewers</th>')
assert module.reviewerCell(ownSmall,[],plan,true) == '&mdash;'
def outsiderReview = copy(ownSmall)
outsiderReview.reviewerDetails = [[id:'external',name:'Outside <Person>',status:'APPROVED']]
assert module.reviewerCell(outsiderReview,[],plan,true).contains('Outside &lt;Person&gt;')
// Include all compact sections and readable display names in the sample.
for (def examplePr : [needsWorkPr,ownLarge,externalSmall,externalLarge,mergedA,mergedA2,mergedB]) {
    examplePr.author.name = config.members.find { it.lanId.toLowerCase() == examplePr.author.id }?.displayName ?: examplePr.author.name
}
def recipientPr = makePr('service-a',101,'lan002','team-a',24,[],'OPEN')
recipientPr.author.name = 'Jan Nowak'
recipientPr.title = 'Update service configuration'
recipientPr.reviewerDetails = [[id:'lan001',name:'Anna Kowalska',status:'APPROVED'],[id:'outside',name:'External Reviewer',status:'CHANGES_REQUESTED']]
def otherOwnedPr = makePr('service-a',102,'lan004','team-a',12,['lan005'],'OPEN')
otherOwnedPr.author.name = 'Piotr Mazur'
otherOwnedPr.title = 'Adjust timeout defaults'

for (def sample : [needsWorkPr,ownLarge,recipientPr,otherOwnedPr,mergedA,mergedA2,externalSmall,externalLarge,mergedB]) {
    def sizeAward = module.calculateSizeAward(config.scoring,sample.diff.changed as Long,4L)
    sample.diff.changedFiles = 4L
    sample.diff.sizeRule = sizeAward.ruleId
    sample.diff.sizePoints = sizeAward.points
    sample.reviewPoints = config.scoring.baseReviewPoints + sizeAward.points
}
def examplePlan = module.aggregateAndPlan(config,[snap('service-a',[needsWorkPr,ownLarge,recipientPr,otherOwnedPr],[mergedA,mergedA2]),snap('service-b',[externalSmall,externalLarge],[mergedB])],now)
module.writeReports(config,examplePlan)
new File('example-report').mkdirs()
for (def entry : files) {
    if (entry.key.endsWith('.html')) {
        new File('example-report',entry.key.tokenize('/').last()).setText(
            entry.value.replace('PR review — ', 'EXAMPLE DATA — PR review — '), 'UTF-8')
    }
}
// Notifications are edge-triggered and reminder thresholds do not repeat.
def notifyPlan = copy(needsWorkPlan)
notifyPlan.openPullRequests[0].reviewActivity = [byMember:[lan002:[status:'PENDING']],actions:[[action:'UPDATED',draft:false,at:now-5*3600000L]]]
def firstNotice = module.notificationDecision('lan002',notifyPlan,[:])
assert firstNotice.send && firstNotice.newKeys == [ownSmall.key]
assert firstNotice.reasons.any { it.startsWith('REVIEW_OVERDUE:') }
assert !module.notificationDecision('lan002',notifyPlan,firstNotice.nextState).send
notifyPlan.collectedAt = now+4*3600000L
def secondNotice = module.notificationDecision('lan002',notifyPlan,firstNotice.nextState)
assert secondNotice.reasons == ['REVIEW_OVERDUE:'+ownSmall.key+':8h']
notifyPlan.collectedAt = now+8*3600000L
def thirdNotice = module.notificationDecision('lan002',notifyPlan,secondNotice.nextState)
assert thirdNotice.reasons == ['REVIEW_OVERDUE:'+ownSmall.key+':12h']
notifyPlan.collectedAt = now+30*3600000L
assert !module.notificationDecision('lan002',notifyPlan,thirdNotice.nextState).send
notifyPlan.openPullRequests[0].reviewActivity.actions[0].at = notifyPlan.collectedAt-1000
assert !module.notificationDecision('lan002',notifyPlan,thirdNotice.nextState).send
notifyPlan.openPullRequests[0].reviewActivity.byMember.lan002.status = 'APPROVED'
notifyPlan.openPullRequests[0].reviewActivity.actions[0].at = now-20*3600000L
assert !module.notificationDecision('lan002',notifyPlan,thirdNotice.nextState).send
// Authors receive changes, not an initial inventory of reviewer statuses.
def authorBaseline = module.notificationDecision('lan001',notifyPlan,[:])
assert !authorBaseline.send
notifyPlan.openPullRequests[0].reviewerDetails = [[id:'external',status:'APPROVED']]
assert module.notificationDecision('lan001',notifyPlan,authorBaseline.nextState).reasons == ['REVIEW_CHANGED:'+ownSmall.key]
assert module.buildIcon([build:[state:'SUCCESSFUL'],merge:[conflicted:true]],true).contains('&#10004;')
assert module.buildIcon([build:[state:'FAILED'],merge:[conflicted:true]],true).contains('&#9888;')
assert !module.buildIcon([build:[state:'UNKNOWN'],merge:[conflicted:true]],false).contains('&#9888;')
assert module.ageCell([createdDate:now-4*3600000L],now).contains('color:#172b4d')
assert module.ageCell([createdDate:now-4*3600000L-1],now).contains('#fff2b3')
assert module.ageCell([createdDate:now-8*3600000L-1],now).contains('#fee2e2')
examplePlan.notifications = [:]
for (String id : examplePlan.members.keySet()) { examplePlan.notifications[id] = module.notificationDecision(id,examplePlan,[:]) }
module.writeReports(config,examplePlan)
for (def entry : files) {
    if (entry.key.endsWith('.html')) { new File('example-report',entry.key.tokenize('/').last()).setText(entry.value, 'UTF-8') }
}
assert module.renderDigest('lan002',examplePlan).contains('<strong>[NEW]</strong>')
// Delivery failure must not acknowledge that recipient; previews never send.
def mailCalls = []
module.metaClass.emailext = { Map args -> mailCalls.add(args); throw new IOException('SMTP failed') }
def deliveryConfig = copy(config); deliveryConfig.notifications.previewOnly = true
module.deliverNotifications(deliveryConfig,examplePlan,[schemaVersion:1,members:[:]])
assert mailCalls.isEmpty()
deliveryConfig.notifications.previewOnly = false
def deliveryPlan = copy(examplePlan)
deliveryPlan.members = [lan002:examplePlan.members.lan002]
def deliveryState = [schemaVersion:1,members:[:]]
module.metaClass.readFile = { Map args -> files['pr-checker-output/member-2.html'] }
try { module.deliverNotifications(deliveryConfig,deliveryPlan,deliveryState); assert false } catch (IOException expected) { }
assert !deliveryState.members.containsKey('lan002')
module.metaClass.readFile = { Map args -> new File(args.file).getText('UTF-8') }
// Marker read/write round trip, identity trust, malformed data and idempotency.
def markerModule = shell.parse(new File('pr-checker/Orchestrator.groovy'))
markerModule.binding.setVariable('env', [GIT_USER:'credential-bot'])
markerModule.metaClass.echo = { Object text -> }
markerModule.metaClass.error = { Object text -> throw new IllegalStateException(text.toString()) }
markerModule.metaClass.readJSON = { Map args -> new JsonSlurper().parseText(args.text) }
markerModule.metaClass.writeJSON = { Map args -> JsonOutput.toJson(args.json) }
def storedComment = null
def markerFresh = [state:'OPEN',draft:false,version:1,fromRef:[latestCommit:'source'],toRef:[latestCommit:'target',displayId:'develop']]
markerModule.metaClass.fetchPagedValues = { String url -> storedComment == null ? [] : [[action:'COMMENTED',comment:storedComment]] }
markerModule.metaClass.executeBitbucketGet = { String url -> url.contains('/comments/') ? storedComment : markerFresh }
assert markerModule.readExistingAssignment('api',config).status == 'NONE'
def markerPr = copy(ownSmall)
markerPr.version=1; markerPr.sourceCommit='source'; markerPr.targetCommit='target'; markerPr.targetBranch='develop'
markerPr.assignment=[status:'NONE',source:'COMMENT',reviewers:[]]
def markerPlan = module.aggregateAndPlan(config,[snap('service-a',[markerPr],[]),snap('service-b',[],[])],now)
def writes = []
markerModule.metaClass.writeAssignmentComment = { String url, String method, Map payload ->
    writes.add(method)
    storedComment = [id:42,version:storedComment == null ? 0 : storedComment.version+1,author:[name:'credential-bot'],text:payload.text]
    storedComment
}
markerModule.persistAssignments(config,markerPlan)
assert writes == ['POST'] && markerPlan.assignments[0].persisted
assert markerPlan.openPullRequests[0].assignment.reviewers == markerPlan.assignments[0].selected
markerModule.persistAssignments(config,markerPlan)
assert writes == ['POST'] // no duplicate comment
markerPlan.assignments[0].selected.add('lan003')
markerModule.persistAssignments(config,markerPlan)
assert writes == ['POST','PUT']
storedComment.author.name='someone-else'
assert markerModule.readExistingAssignment('api',config).status == 'NONE'
storedComment.author.name='credential-bot'
storedComment.text='[jenkins-pr-review:v1]\nnot-json'
assert markerModule.readExistingAssignment('api',config).status == 'UNKNOWN'
storedComment.text='[jenkins-pr-review:v1]\n{"schemaVersion":1,"reviewers":["unknown"]}'
assert markerModule.readExistingAssignment('api',config).status == 'INVALID'
markerModule.metaClass.fetchPagedValues = { String url -> [[action:'COMMENTED',comment:storedComment],[action:'COMMENTED',comment:storedComment+[id:43]]] }
assert markerModule.readExistingAssignment('api',config).status == 'AMBIGUOUS'
// Stale source fails before any write.
markerModule.metaClass.fetchPagedValues = { String url -> [] }
markerPr.assignment=[status:'NONE',source:'COMMENT',reviewers:[]]
markerPlan.openPullRequests[0].assignment=markerPr.assignment
markerFresh.fromRef.latestCommit='new-source'
reject { markerModule.persistAssignments(config,markerPlan) }
assert writes == ['POST','PUT']
markerModule.binding.setVariable('env', [:])
reject { markerModule.readExistingAssignment('api',config) }
assert !original.containsKey('assignments')
// Every parallel closure binds its own repository and output directory.
def branchRepos = []
def branchDirs = []
module.metaClass.dir = { String path, Closure body -> branchDirs.add(path); body() }
module.metaClass.usernamePassword = { Map args -> args }
module.metaClass.withCredentials = { List args, Closure body -> body() }
module.metaClass.collectRepository = { Map cfg, Map repo, long time ->
    branchRepos.add(repo.id); [repositoryId:repo.id]
}
def branches = config.repositories.collect { module.repositoryBranch(config,it,now) }
branches.reverse().each { it() }
assert branchRepos == ['service-b','service-a']
assert branchDirs == ['pr-checker-output/repos/service-b','pr-checker-output/repos/service-a']
// A parallel branch exposes the actual validation error and rethrows it unchanged.
def originalFailure = new IllegalStateException('Missing changed files in diff stats')
module.metaClass.collectRepository = { Map cfg, Map repo, long time -> throw originalFailure }
try { module.repositoryBranch(config,config.repositories[0],now)(); assert false }
catch (IllegalStateException caught) { assert caught.is(originalFailure) }
assert logs.any { it.contains('[service-a] COLLECTION FAILED: Missing changed files in diff stats') }
// Parse Jenkinsfile (the Declarative DSL is not executed locally).
def pipeline = shell.parse(new File('Jenkinsfile-PR-checker.groovy'))
pipeline.metaClass.pipeline = { Closure body -> }
pipeline.run()
println 'PASS: configuration, identity, collection, paging, status fallbacks, global scoring, policies, deterministic planning, JSON transfer and HTML reports.'
println 'Groovy/SnakeYAML with mocked Jenkins steps only; no live Jenkins CPS/Sandbox or Bitbucket integration.'

