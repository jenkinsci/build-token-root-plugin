Usage
-----

The use case is that Jenkins is secured so that anonymous users lack
overall read permissions. Say you want to triggers builds of certain
jobs from a script. You can pick a sufficiently authenticated user and
use that person's API token to POST to `job/NAME/build`. But this grants
that person's full permissions to anyone who can see the script, which
is hazardous.

The usual workaround for this issue is to define a build authorization
token in job configuration, and have the script ping
`job/NAME/build?token=SECRET`. Unfortunately Jenkins checks URIs
hierarchically and just getting as far as `job/NAME/` requires
authentication.

This plugin offers an alternate URI pattern which is not subject to the
usual overall or job read permissions. Just issue an Http GET or POST to
`buildByToken/build?job=NAME&token=SECRET`. This URI is accessible to
anonymous users regardless of security setup, so you only need the right
token.

Folder are supported. If a job named `myJob` is inside a folder named
`myFolder`, then the `NAME` is `myFolder/myJob` (without the
interleaving `job/` string).

(The variant sub-URIs `buildWithParameters` and `polling` are also
supported, as is the usual `delay` query parameter.)

The server replies with a “201 Created” status code when a build is
queued successfully. When a build is already scheduled, the server
replies with a “303 See Other”, the `Location` header pointing to the
scheduled build URL. Clients without the `READ` permission on the build
should not follow the redirect, as it will lead to a page they do not
have permission to see.

To create a token for your job, go to the job configuration, select
**Trigger Builds Remotely** in the build triggers section. The token
you set here is what you will pass via the url.

Examples
--------

Trigger the **RevolutionTest** job with the token **TacoTuesday**:

    buildByToken/build?job=RevolutionTest&token=TacoTuesday

Trigger the **RevolutionTest** job with the token **TacoTuesday** and
parameter **Type** supplied with the value **Mexican**:

    buildByToken/buildWithParameters?job=RevolutionTest&token=TacoTuesday&Type=Mexican

Changelog
---------

### Version 1.5 and newer

See GitHub releases.

### Version 1.4 (2016 May 03)

-   [JENKINS-25637](https://issues.jenkins-ci.org/browse/JENKINS-25637)
    Do not require a CSRF crumb to trigger a build.

### Version 1.3 (2015 Aug 11)

-   [JENKINS-22849](https://issues.jenkins-ci.org/browse/JENKINS-22849)
    Include queue item location in HTTP response, just like the core
    endpoint does.

### Version 1.2 (2015 Mar 08)

-   [JENKINS-26693](https://issues.jenkins-ci.org/browse/JENKINS-26693)
    Ability to trigger Workflow builds.

### Version 1.1 (2014 Feb 10)

-   Added logging to make it easier to diagnose why a given request was
    rejected. As a Jenkins admin, create a logger covering
    `org.jenkinsci.plugins.build_token_root` at `FINE` or below.

### Version 1.0 (2013 May 14)

-   Initial release.
