# quarkus-github-probot-app-example-aws-sam

A [GitHub App](https://docs.github.com/en/apps) built with the
[Quarkus GitHub App](https://github.com/quarkiverse/quarkus-github-app) extension that listens for newly
opened issues and leaves a `Hello word!` comment on them.

The app is packaged with the
[`quarkus-amazon-lambda-http`](https://quarkus.io/extensions/io.quarkus/quarkus-amazon-lambda-http/) extension and
deployed as an AWS Lambda function behind an API Gateway HTTP API using the
[AWS SAM CLI](https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/what-is-sam.html).
It runs on [Amazon Corretto](https://aws.amazon.com/corretto/) 25, the latest LTS release of Java (Lambda `java25`
runtime).

## Project layout

| Path | Description |
| --- | --- |
| `src/main/java/com/example/HelloIssue.java` | Listener for `issues.opened` events that comments `Hello word!` |
| `src/test/java/com/example/HelloIssueTest.java` | Unit tests, using `quarkus-github-app-testing` to mock GitHub |
| `template.yaml` | AWS SAM template (Lambda function + HTTP API), outputs the webhook URL |
| `.envrc-sample` | Sample environment variables for running locally |
| `.vscode/` | VS Code launch configurations to debug the app and the unit tests |
| `.devcontainer/` | Dev container with all the tools needed to develop, run and deploy the app |
| `.github/workflows/ci.yml` | Runs the unit tests on pull requests and on pushes to `main` |

## Dependencies

To develop and run the app locally you need:

* [Amazon Corretto 25](https://aws.amazon.com/corretto/) (or any Java 25
  JDK). Maven doesn't need to be installed, use the included Maven wrapper (`./mvnw`).
* [Node.js](https://nodejs.org/) (LTS) and the [smee.io client](https://github.com/probot/smee-client)
  (`npm install --global smee-client`) - optional, the Quarkus GitHub App extension can connect to smee.io by itself
  (see below), but the client is handy to replay payloads.
* [direnv](https://direnv.net/) - optional, to automatically load the environment variables from `.envrc`.
* [Docker](https://docs.docker.com/get-docker/) - optional, only needed for `sam local invoke`.

To deploy the app you also need:

* An AWS account and the [AWS CLI](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html)
  configured with credentials (`aws configure`).
* The [AWS SAM CLI](https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/install-sam-cli.html).

All of these are pre-installed in the [dev container](#dev-container).

## Create the GitHub App

1. Go to <https://smee.io/> and click **Start a new channel**. Copy the channel URL (e.g.
   `https://smee.io/AbCdEf123456`), it is used as the webhook URL while developing locally.
2. [Register a new GitHub App](https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/registering-a-github-app)
   (**Settings** > **Developer settings** > **GitHub Apps** > **New GitHub App**) with:
   * **Webhook URL**: your smee.io channel URL (replace it with the `WebhookUrl` output of the stack once deployed).
   * **Webhook secret**: a random string, e.g. generated with `openssl rand -hex 32`.
   * **Repository permissions** > **Issues**: **Read and write**.
   * **Subscribe to events**: **Issues**.
3. Once created, note the **App ID** and generate a **private key** (a `.pem` file is downloaded). Save it in the
   project root as `app.private-key.pem` (`*.pem` files are git-ignored).
4. Install the app on a repository you want to test it with.

## Environment variables (local run)

The app is configured through the following environment variables:

| Variable | Required | Description |
| --- | --- | --- |
| `QUARKUS_GITHUB_APP_APP_ID` | yes | The App ID of the GitHub App |
| `QUARKUS_GITHUB_APP_WEBHOOK_SECRET` | yes | The webhook secret configured on the GitHub App |
| `QUARKUS_GITHUB_APP_PRIVATE_KEY` | yes | The content of the private key (PEM) of the GitHub App |
| `QUARKUS_GITHUB_APP_WEBHOOK_PROXY_URL` | dev mode only | The smee.io channel URL to receive the webhook payloads from |

Copy the sample file and fill in your values:

```shell
cp .envrc-sample .envrc
# edit .envrc
direnv allow        # or: source .envrc
```

`.envrc` is git-ignored, never commit it.

## Run the application locally

With the environment variables set, start the app in [Quarkus dev mode](https://quarkus.io/guides/dev-mode-differences):

```shell
./mvnw quarkus:dev
```

The app listens on <http://localhost:8080/> and, because `QUARKUS_GITHUB_APP_WEBHOOK_PROXY_URL` is set, the Quarkus
GitHub App extension connects to your smee.io channel and forwards every payload delivered to it to the app. Open a
new issue in a repository where the app is installed: a `Hello word!` comment is added to it.
Payload signature checking is disabled in dev mode, and changes to the code are live-reloaded.

The payloads captured by smee.io are visible on the channel page (`https://smee.io/<channel>`), where they can be
inspected and replayed with the **Redeliver** button. If you prefer to use the smee client instead of the built-in
proxy, unset `QUARKUS_GITHUB_APP_WEBHOOK_PROXY_URL` and run in another terminal:

```shell
smee --url https://smee.io/<channel> --target http://localhost:8080/
```

> [!TIP]
> Dev mode listens for a debugger on port `5005`. In VS Code, use the **Debug GitHub App (Quarkus dev mode)** launch
> configuration, which loads `.envrc`, starts dev mode and attaches the debugger.

## Run the unit tests

```shell
./mvnw test
```

The tests use [`quarkus-github-app-testing`](https://github.com/quarkiverse/quarkus-github-app/blob/main/docs/modules/ROOT/pages/testing.adoc) to send
sample payloads (`src/test/resources/*.json`) to the app and verify the interactions with a mocked GitHub API, no
GitHub App credentials are needed. In VS Code, use the **Debug Unit Tests** launch configuration to run them with the
debugger attached.

## Build and deploy

Build the Lambda deployment package (`target/function.zip`):

```shell
./mvnw clean package
```

Deploy the stack with SAM. The first time, use the guided mode which prompts for the parameters and saves your
answers to `samconfig.toml` (git-ignored):

```shell
sam deploy --guided
```

When prompted for `GitHubAppPrivateKey`, paste the private key on a single line (line breaks can be replaced by
spaces). Alternatively pass all the parameters on the command line:

```shell
sam deploy \
  --stack-name hello-github-app \
  --resolve-s3 \
  --capabilities CAPABILITY_IAM \
  --parameter-overrides \
    GitHubAppId="$QUARKUS_GITHUB_APP_APP_ID" \
    GitHubAppWebhookSecret="$QUARKUS_GITHUB_APP_WEBHOOK_SECRET" \
    GitHubAppPrivateKey="$(tr '\n' ' ' < app.private-key.pem)"
```

Once deployed, SAM prints the stack outputs. Copy the `WebhookUrl` output and set it as the **Webhook URL** of your
GitHub App (**Settings** > **Developer settings** > **GitHub Apps** > your app > **General**). The outputs can also be
displayed with:

```shell
sam list stack-outputs --stack-name hello-github-app
```

To redeploy after a change, run `./mvnw clean package && sam deploy`. To delete the stack, run
`sam delete --stack-name hello-github-app`.

### Deployment parameters

| Parameter | Description |
| --- | --- |
| `GitHubAppId` | The App ID of the GitHub App |
| `GitHubAppWebhookSecret` | The webhook secret configured on the GitHub App (`NoEcho`) |
| `GitHubAppPrivateKey` | The private key (PEM) of the GitHub App, line breaks may be replaced by spaces (`NoEcho`) |

The parameters are exposed to the function as the `QUARKUS_GITHUB_APP_*` environment variables described above.
In production the webhook payload signatures are verified with the webhook secret. The function uses
[Lambda SnapStart](https://docs.aws.amazon.com/lambda/latest/dg/snapstart.html) to reduce cold starts.

### Stack outputs

| Output | Description |
| --- | --- |
| `WebhookUrl` | The webhook URL to set in the GitHub App configuration |
| `GitHubAppFunctionArn` | The ARN of the Lambda function |

### Test the Lambda function locally (optional)

With Docker running, the packaged function can be invoked locally with an API Gateway HTTP API event, see
[`sam local invoke`](https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/using-sam-cli-local-invoke.html).
Note that the payload must be signed with the webhook secret (`X-Hub-Signature-256` header) as signature checking is
enabled in the packaged application.

## Dev container

The `.devcontainer` folder defines a [dev container](https://containers.dev/) (usable with VS Code or GitHub
Codespaces) with Amazon Corretto 25, Node.js and the smee client, the AWS CLI, the AWS SAM CLI, Docker and direnv.
Open the repository in the container, copy `.envrc-sample` to `.envrc`, fill it in, run `direnv allow` and you're
ready to go.

## Continuous integration

The [CI workflow](.github/workflows/ci.yml) runs the unit tests with Amazon Corretto 25 on every pull request and on
every push to `main`.
