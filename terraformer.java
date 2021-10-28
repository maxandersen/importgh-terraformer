//usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 14+
//JAVAC_OPTIONS --enable-preview -source 14
//JAVA_OPTIONS --enable-preview 
// -XX:StartFlightRecording=filename=terraformer.jfr,settings=file.jfc
//DEPS info.picocli:picocli:4.2.0
//DEPS org.kohsuke:github-api:1.115
//DEPS com.fasterxml.jackson.core:jackson-databind:2.2.3
//DEPS  io.quarkus.qute:qute-core:1.7.0.Final
//DEPS org.jboss.resteasy:resteasy-client:4.5.6.Final
//DEPS org.jboss.resteasy:resteasy-jackson2-provider:4.5.6.Final


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.qute.*;
import org.kohsuke.github.*;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.GenericType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static java.lang.System.exit;
import static java.lang.System.out;

@Command(name = "importgh", mixinStandardHelpOptions = true, version = "importgh 0.1",
        description = "importgh made with jbang")
class terraformer implements Callable<Integer> {

    //private state
    private GitHub gh;
    private Engine qute;
    private GHOrganization org;

    @CommandLine.Option(names = { "--token" }, description = "Github Token", defaultValue = "${GITHUB_TOKEN}")
    private String token;

    @CommandLine.Option(names = { "--org" }, description = "Which github organization to import", required=true, defaultValue = "${GITHUB_ORG}")
    private String orgname;

    @CommandLine.Option(names = {"--parts"}, description = "What resources groups to include", defaultValue = "teams,labels")
    private Set<String> parts;

    public static void main(String... args) {
        int exitCode = new CommandLine(new terraformer()).execute(args);
        exit(exitCode);
    }

    String quote(String v) {
        return "\"" + v + "\"";
    }

    String pair(String k, String v) {
        return k + " = " + v;
    }

    String tfname(String name) {

        return name
                .replace(".", "-")
                .replace(" ", "_")
                .replace("/", "_")
                .replace("-", "_");
    }


    List<String> dumpRepositories(Map<String, GHRepository> repos) throws IOException {
        List<String> resources = new ArrayList<>();

        for (Map.Entry<String, GHRepository> entry : repos.entrySet()) {
            String name = entry.getKey();
            GHRepository repo = gh.getRepository(orgname + "/" + name);

            // REPO
            //https://www.terraform.io/docs/providers/github/r/repository.html
            String repoTemplate = """
            resource "github_repository" "{repo.name.safeName()}" {
                         name                    = "{repo.name}"
                         description             = "{repo.description}"
                         homepage_url            = "{repo.homepage}"
                         private                 = {repo.private}
                         has_issues              = {repo.hasIssues()}
                         has_projects            = {repo.hasProjects()}
                         has_wiki                = {repo.hasWiki()}
                         {! is_template            = N/A !}
                         allow_merge_commit      = {repo.isAllowMergeCommit()}
                         allow_squash_merge      = {repo.allowSquashMerge}
                         allow_rebase_merge      = {repo.allowRebaseMerge}
                         delete_branch_on_merge  = {repo.isDeleteBranchOnMerge()}
                         has_downloads           = {repo.hasDownloads()}
                         {! auto_init            = N/A !}
                         {! gitignore_template   = N/A !}
                         {! license_template     = N/A !}
                         default_branch          = "{repo.defaultBranch}"
                         archived                = {repo.archived}
                         {! template               = N/A !}
                         topics                  = [
                         {#each repo.listTopics()}
                           "{it}"{#if hasNext},{/if}
                         {/each}
                         ]
                       }
            """;
            StringBuffer output = new StringBuffer(qute.parse(repoTemplate)
                    .data("repo", repo).render());

            String imports = "github_repository.{repo.name.safeName()} \"{repo.name}\"";
            resources.add(qute.parse(imports)
                    .data("repo", repo).render());

            // Collaborators
            // can't get right permission info and noone should use collaborators anyways ;)
            /*var users = repo.listCollaborators();
            String collabTemplate = """
                     resource "github_repository_collaborator" "{repo.name.safeName()}_{user.login.safeName()}" {
                         repository = "{repo.name}"
                         username   = "{user.login}"
                         permission = "{permission.toString().toLowerCase()}"
                       }
                    """;
            users.forEach(user -> {
                GHPermissionType permission = null;

                try {
                    permission = repo.getPermission(user);
                } catch (IOException e) {
                    e.printStackTrace();
                }

                output.append("\n\n" + qute.parse(collabTemplate).data("user", user).data("repo", repo).data("permission", permission).render());

                String collab = "github_repository_collaborator.{repo.name.safeName()}_{user.login.safeName()} \"{repo.name}:{user.login}\"";
                resources.add(qute.parse(collab)
                        .data("user", user).data("repo", repo).render());
            });
            */

            if(parts.contains("labels")) {
                var labels = repo.listLabels();
                String labelTemplate = """
                         resource "github_issue_label" "{repo.name.safeName()}_{label.name.safeName()}" {
                            repository  = "{repo.name}"
                            name        = "{label.name}"
                            color       = "{label.color}"
                            description = "{label.description}"
                          }
                        """;
                labels.forEach(label -> {
                    output.append("\n\n").append(qute.parse(labelTemplate).data("label", label).data("repo", repo).render());

                    String importsx = "github_issue_label.{repo.name.safeName()}_{label.name.safeName()} \"{repo.name}:{label.name}\"";
                    resources.add(qute.parse(importsx)
                            .data("label", label).data("repo", repo).render());
                });

            }
            String filename = "imported-repo-" + tfname(repo.getName()) + ".tf";
            out.println("Writing " + filename);
            Path p = Path.of(filename);
            if(p.getParent()!=null) {
                Files.createDirectories(p.getParent());
            }
            Files.writeString(p, output);


        }


        return resources;
    }

    @Override
    public Integer call() throws Exception { // your business logic goes here...

        setupQute();

        login();

        org = gh.getOrganization(orgname);
        var repos = org.getRepositories();
        var resources = dumpRepositories(repos);


        resources.addAll(dumpMemberships("admin", org.listMembersWithRole("admin").toList()));
        resources.addAll(dumpMemberships("member", org.listMembersWithRole("member").toList()));
        resources.addAll(dumpTeams(org.getTeams()));

        final StringBuffer imports = new StringBuffer();
        resources.forEach(x -> imports.append("terraform import ").append(x).append("\n"));
        Files.writeString(Path.of("imported-state.sh"), imports);

        return CommandLine.ExitCode.OK;
    }

    private void login() throws IOException {


        var ghb = (new GitHubBuilder());

        if(token!=null) {
            gh = ghb.withOAuthToken(token).build();
        } else {
            gh = ghb.build();
        }
    }

    private void setupQute() {
        qute = Engine.builder()
                .addDefaults()
                .removeStandaloneLines(true)
                .addValueResolver(new ReflectionValueResolver())
                .addValueResolver(new ValueResolver() {
                    @Override
                    public boolean appliesTo(EvalContext context) {
                        return context.getBase() instanceof String && context.getName().equals("safeName");
                    }

                    @Override
                    public CompletionStage<Object> resolve(EvalContext context) {
                        String base = (String) context.getBase();
                        return CompletableFuture.completedFuture(tfname(base));
                    }

                })
                .addResultMapper(new ResultMapper() {

                    @Override
                    public boolean appliesTo(TemplateNode.Origin origin, Object result) {
                        return result.equals(Results.Result.NOT_FOUND);
                    }

                    @Override
                    public String map(Object result, Expression expression) {
                        var origin = expression.getOrigin();
                        throw new IllegalArgumentException(
                                expression.toOriginalString() + " @ " + origin + " at char " + origin.getLineCharacter() + " could not be resolved");
                    }
                })
                .build();
    }

    private List<String> dumpMemberships(String role, List<GHUser> entities) throws IOException {
        List<String> resources = new ArrayList<>();

        StringBuffer memberships = new StringBuffer();

        for (GHUser user : entities) {

            String template = """
                    resource "github_membership" "{it.login}" {
                       username = "{it.login}"
                       role     = "{role}"
                     }
                    """;

            String output = qute.parse(template)
                    .data("it", user)
                    .data("role", role)
                    .render();

            memberships.append(output).append("\n");

            String imports = "github_membership.{it.login} {org}:{it.login}";
            resources.add(qute.parse(imports)
                    .data("it", user)
                    .data("org",org.getLogin()).render());
        }

        String filename = "imported-" + role + "-memberships.tf";
        out.println("Writing " + filename);
        Path p = Path.of(filename);
        if(p.getParent()!=null) {
            Files.createDirectories(p.getParent());
        }
        Files.writeString(p, memberships);

        return resources;
    }

    private List<String> dumpTeams(Map<String, GHTeam> teams) throws IOException {
        List<String> resources = new ArrayList<>();

        for (Map.Entry<String, GHTeam> entry : teams.entrySet()) {
            GHTeam team = entry.getValue();

            String template = """
                    resource "github_team" "{it.name.safeName()}" {
                      name        = "{it.name}"
                      description = "{it.description}"
                      privacy     = "{it.privacy.toString().toLowerCase()}"
                    }
                    """;

            StringBuffer output = new StringBuffer(qute.parse(template)
                    .data("it", team).render());

            var client = ClientBuilder.newClient();
            Set<String> maintainers = new HashSet<>();
            try(var thing = client.target("https://api.github.com/orgs/" + orgname + "/teams/" + team.getSlug() + "/members?role=maintainer")
            .request().header("Authorization" ,"token " + token).get()) {
                    List<Maintainer> s = thing.readEntity(new GenericType<>() {
                    });
                  //  System.out.println(s);
                    s.forEach(x -> maintainers.add(x.login));
            }
            client.close();

            for(GHUser user : team.getMembers()) {
                String mtemplate = """
                        resource "github_team_membership" "{team.name.safeName()}_{it.login.safeName()}" {
                           team_id  = "{team.id}"
                           username = "{it.login}"
                           role     = "{role}"
                         }
                        """;

                output.append("\n").append(qute.parse(mtemplate)
                        .data("team", team)
                        .data("it", user)
                        .data("role", maintainers.contains(user.getLogin()) ? "maintainer" : "member")
                        .render());

                String imports = "github_team_membership.{team.name.safeName()}_{it.login.safeName()} {team.id}:{it.login}";
                resources.add(qute.parse(imports)
                        .data("team", team)
                        .data("it", user)
                        .render());

            }




            for(var repo : team.getRepositories().values()) {

                String rtemplate = """
                        resource "github_team_repository" "{team.name.safeName()}_{repo.name.safeName()}" {
                            team_id    = "{team.id}"
                            repository = "{repo.name}"
                            permission = "{team.permission}"
                          }
                        """;
                output.append("\n").append(qute.parse(rtemplate)
                        .data("team", team)
                        .data("repo", repo)
                        .render());

                String imports = "github_team_repository.{team.name.safeName()}_{repo.name.safeName()} {team.id}:{repo.name}";
                resources.add(qute.parse(imports)
                        .data("team", team)
                        .data("repo", repo)
                        .render());

            }


            String filename = "imported-team-" + tfname(team.getName()) + ".tf";
            out.println("Writing " + filename);
            Path p = Path.of(filename);
            if(p.getParent()!=null) {
                Files.createDirectories(p.getParent());
            }
            Files.writeString(p, output);

            String imports = "github_team.{it.name.safeName()} {it.id}";
            resources.add(qute.parse(imports)
                    .data("it", team).render());
        }

        return resources;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Maintainer {
        public String login;

    }
}
