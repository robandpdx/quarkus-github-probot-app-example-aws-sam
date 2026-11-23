# quarkus-github-probot-app-example-aws-sam

A sample [GitHub App](https://docs.github.com/en/apps) built with the
[Quarkus GitHub App](https://github.com/quarkiverse/quarkus-github-app) extension that listens for newly
opened issues and leaves a `Hello word!` comment on them.

The app is packaged with the
[`quarkus-amazon-lambda-http`](https://quarkus.io/extensions/io.quarkus/quarkus-amazon-lambda-http/) extension and
deployed as an AWS Lambda function behind an API Gateway HTTP API using the
[AWS SAM CLI](https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/what-is-sam.html).
It runs on [Amazon Corretto](https://aws.amazon.com/corretto/) 25, the latest LTS release of Java (Lambda `java25`
runtime).

The issue listener uses a synchronous CDI observer (`@Observes GitHubEvent`) rather than `@Issue.Opened`, whose
dispatch runs asynchronously in production. This keeps the Lambda invocation active until the comment is posted:
the webhook receives HTTP 200 only after processing finishes, or HTTP 500 if commenting fails. Detached background
work can be frozen when Lambda returns its response. The extension still verifies webhook signatures and provides
the authenticated installation client.

## Project layout

| Path | Description |
| --- | --- |
| `src/main/java/com/example/HelloIssue.java` | Listener for `issues.opened` events that comments `Hello word!` |
| `src/test/java/com/example/HelloIssueTest.java` | Unit tests, using `quarkus-github-app-testing` to mock GitHub |
| `src/main/java/com/example/GitHubAppSecretsConfigSourceFactory.java` | Loads missing GitHub App configuration from Secrets Manager at runtime |
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

Environment variables take precedence, so local development does not need AWS credentials or access to Secrets
Manager when all three GitHub App values are set:

| Variable | Required | Description |
| --- | --- | --- |
| `QUARKUS_GITHUB_APP_APP_ID` | unless supplied by Secrets Manager | The App ID of the GitHub App |
| `QUARKUS_GITHUB_APP_WEBHOOK_SECRET` | unless supplied by Secrets Manager | The webhook secret configured on the GitHub App |
| `QUARKUS_GITHUB_APP_PRIVATE_KEY` | unless supplied by Secrets Manager | The content of the private key (PEM) of the GitHub App |
| `GITHUB_APP_SECRET_ARN` | only for Secrets Manager fallback | ARN of the existing JSON secret described below |
| `QUARKUS_GITHUB_APP_WEBHOOK_PROXY_URL` | dev mode only | The smee.io channel URL to receive the webhook payloads from |

Copy the sample file and fill in your values:

```shell
cp .envrc-sample .envrc
# edit .envrc
direnv allow        # or: source .envrc
```

`.envrc` is git-ignored, never commit it.

If `GITHUB_APP_SECRET_ARN` is set and any GitHub App values are missing, the app retrieves the secret once during
runtime configuration initialization and uses it for the missing values only. Unset variables you want to obtain
from Secrets Manager rather than setting them to empty strings. Standard Quarkus configuration, such as system
properties, is also honored. AWS is not contacted when all three values are already configured, even if an ARN is set.
For local fallback, configure your AWS credentials and region using the usual AWS SDK credential and region chains
(for example, an AWS profile and `AWS_REGION`). Lambda uses its execution role and region automatically.
An unreadable, malformed, or incomplete secret fails startup rather than silently using empty credentials.

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

### Prepare the secret separately

Create and manage a Secrets Manager secret independently of this stack, in the same AWS account and region as the
Lambda. Store a JSON `SecretString` with these string fields:

```json
{
  "APP_ID": "123456",
  "WEBHOOK_SECRET": "your-webhook-secret",
  "PRIVATE_KEY": "-----BEGIN RSA PRIVATE KEY-----\n...\n-----END RSA PRIVATE KEY-----"
}
```

These JSON field names are case-sensitive, and all values must be non-blank strings (`APP_ID` must be quoted).
The local `QUARKUS_GITHUB_APP_*` environment variable names are unchanged.

In the Secrets Manager console's key/value editor, enter the PEM with its actual line breaks. In JSON, encode those
line breaks as `\n`. Alternatively, `PRIVATE_KEY` can contain standard base64 encoding of the **entire PEM file**,
including its header and footer. Whitespace and line breaks in that base64 value are accepted. The app decodes it
once before passing the PEM to the GitHub App extension; plain PEM values are preserved. The decoded key must be
an unencrypted RSA private key with a `-----BEGIN RSA PRIVATE KEY-----` or `-----BEGIN PRIVATE KEY-----` header.
Malformed base64 or decoded data without a supported private-key header fails startup without exposing the key.
This decoding applies only to Secrets Manager's `PRIVATE_KEY`; local environment variables still expect plain PEM.

Copy the secret's full ARN, including its generated suffix.
The stack neither creates nor updates nor deletes this secret.

The template grants the function only `secretsmanager:GetSecretValue` on the supplied ARN. It works with the default
AWS-managed `aws/secretsmanager` encryption key. If you use a customer-managed KMS key, also grant `kms:Decrypt` on
that key to the function's execution role and allow the role in the key policy.

### Deploy with the secret ARN

Deploy the stack with SAM. The first time, use guided mode, which prompts for `GitHubAppSecretArn` and the
non-secret `GitHubAppSecretsRevision` (default `1`), not the secret values, and saves deployment settings to
`samconfig.toml` (git-ignored):

```shell
sam deploy --guided
```

Alternatively, pass the ARN and non-secret revision on the command line:

```shell
sam deploy \
  --stack-name hello-github-app \
  --resolve-s3 \
  --capabilities CAPABILITY_IAM \
  --parameter-overrides \
    GitHubAppSecretArn=arn:aws:secretsmanager:us-east-1:123456789012:secret:github-app-AbCdEf \
    GitHubAppSecretsRevision=1
```

No GitHub App credentials are passed as deployment parameters or stored in the Lambda environment.
If upgrading an existing deployment, remove the old `GitHubAppId`, `GitHubAppWebhookSecret`, and
`GitHubAppPrivateKey` parameter overrides from your local `samconfig.toml` and replace them with `GitHubAppSecretArn`.

Once deployed, SAM prints the stack outputs. Copy the `WebhookUrl` output and set it as the **Webhook URL** of your
GitHub App (**Settings** > **Developer settings** > **GitHub Apps** > your app > **General**). The outputs can also be
displayed with:

```shell
sam list stack-outputs --stack-name hello-github-app
```

To redeploy after a change, run `./mvnw clean package` and then `sam deploy` (this short form relies on the
`samconfig.toml` written by `sam deploy --guided`; if you deployed with the command-line form, repeat the full command). To delete the stack, run
`sam delete --stack-name hello-github-app`.

### Deployment parameters

| Parameter | Description |
| --- | --- |
| `GitHubAppSecretArn` | Full ARN of an existing Secrets Manager JSON secret containing `APP_ID`, `WEBHOOK_SECRET`, and `PRIVATE_KEY` |
| `GitHubAppSecretsRevision` | Non-secret revision, default `1`; change it to publish a fresh Lambda version after rotating secrets |

The ARN and non-secret revision are exposed as `GITHUB_APP_SECRET_ARN` and `GITHUB_APP_SECRETS_REVISION`;
secret values are fetched at runtime. The application does not read the revision: changing the environment
configuration triggers version publishing.
In production the webhook payload signatures are verified with the webhook secret. The function uses
[Lambda SnapStart](https://docs.aws.amazon.com/lambda/latest/dg/snapstart.html) to reduce cold starts.

Secret values are cached for the initialized application, including in its SnapStart snapshot. After rotating the
secret, change `GitHubAppSecretsRevision` to a new value (for example, from `1` to `2`) in your saved
`samconfig.toml` parameter overrides and run `sam deploy`. No application rebuild is needed for a revision-only
deployment. SAM publishes a fresh Lambda version and SnapStart snapshot and moves the `live` alias to it.
If passing `--parameter-overrides` on the command line instead, include both the ARN and the new revision, since
command-line overrides replace the saved overrides.

The template declares `AWS::LanguageExtensions` before `AWS::Serverless-2016-10-31` so parameter references are
resolved before SAM calculates the published version. Updating the external secret alone, or redeploying with
an unchanged revision and artifact, does not refresh existing snapshots.

The template sets `JAVA_TOOL_OPTIONS` to open the Java modules required by Quarkus. The managed Lambda runtime
does not launch the application with `java -jar`, so the deployment archive's `Add-Opens` manifest entry does not
take effect. In particular, Java 25 requires `java.base/java.lang` to be opened for JBoss Threads; without it,
initialization fails and the SnapStart version cannot stabilize.

If an initial deployment ends in `ROLLBACK_COMPLETE`, fix the startup error and delete the failed stack before
deploying it again; CloudFormation cannot update a stack in that state. Inspect the Lambda's CloudWatch log
stream for the underlying initialization error.

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
