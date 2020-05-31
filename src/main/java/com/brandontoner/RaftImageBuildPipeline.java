package com.brandontoner;

import software.amazon.awscdk.core.App;
import software.amazon.awscdk.core.SecretValue;
import software.amazon.awscdk.core.Stack;
import software.amazon.awscdk.core.StackProps;
import software.amazon.awscdk.services.codebuild.BuildEnvironment;
import software.amazon.awscdk.services.codebuild.BuildSpec;
import software.amazon.awscdk.services.codebuild.LinuxBuildImage;
import software.amazon.awscdk.services.codebuild.PipelineProject;
import software.amazon.awscdk.services.codepipeline.Artifact;
import software.amazon.awscdk.services.codepipeline.Pipeline;
import software.amazon.awscdk.services.codepipeline.StageProps;
import software.amazon.awscdk.services.codepipeline.actions.CodeBuildAction;
import software.amazon.awscdk.services.codepipeline.actions.EcsDeployAction;
import software.amazon.awscdk.services.codepipeline.actions.GitHubSourceAction;
import software.amazon.awscdk.services.codepipeline.actions.GitHubTrigger;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecs.FargateService;
import software.amazon.awscdk.services.ecs.patterns.ApplicationLoadBalancedFargateService;
import software.amazon.awscdk.services.iam.PolicyStatement;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

class RaftImageBuildPipeline extends Stack {
    public RaftImageBuildPipeline(App app,
                                  String id,
                                  StackProps stackProps,
                                  Repository ecr,
                                  FargateService service) throws IOException {
        super(app, id, stackProps);
        PipelineProject lambdaBuild = PipelineProject.Builder.create(this, "EcsBuild")
                                                             .buildSpec(BuildSpec.fromObject(new HashMap<String, Object>() {{
                                                                 put("version", "0.2");
                                                                 put("phases", new HashMap<String, Object>() {{
                                                                     put("install", new HashMap<String, Object>() {{
                                                                         put("runtime-versions",
                                                                             new HashMap<String, String>() {{
                                                                                 put("java", "openjdk11");
                                                                                 put("docker", "18");
                                                                             }});
                                                                     }});
                                                                     put("pre_build",
                                                                         new HashMap<String, List<String>>() {{
                                                                             put("commands",
                                                                                 Arrays.asList(
                                                                                         "echo Logging in to Amazon ECR...",
                                                                                         "$(aws ecr get-login --region $AWS_DEFAULT_REGION --no-include-email)",
                                                                                         "REPOSITORY_URI="
                                                                                         + ecr.getRepositoryUri(),
                                                                                         "COMMIT_HASH=$(echo $CODEBUILD_RESOLVED_SOURCE_VERSION | cut -c 1-7)",
                                                                                         "IMAGE_TAG=${COMMIT_HASH:=latest}"));
                                                                         }});
                                                                     put("build", new HashMap<String, List<String>>() {{
                                                                         put("commands",
                                                                             Arrays.asList("mvn install",
                                                                                           "docker build -t $REPOSITORY_URI:latest .",
                                                                                           "docker tag $REPOSITORY_URI:latest $REPOSITORY_URI:$IMAGE_TAG"));
                                                                     }});
                                                                     put("post_build",
                                                                         new HashMap<String, List<String>>() {{
                                                                             put("commands",
                                                                                 Arrays.asList(
                                                                                         "docker push $REPOSITORY_URI:latest",
                                                                                         "docker push $REPOSITORY_URI:$IMAGE_TAG",
                                                                                         "echo Writing image definitions file...",
                                                                                         "printf '[{\"name\":\"web\",\"imageUri\":\"%s\"}]' $REPOSITORY_URI:$IMAGE_TAG > imagedefinitions.json"));
                                                                         }});
                                                                 }});
                                                                 put("artifacts", new HashMap<String, Object>() {{
                                                                     put("files",
                                                                         Arrays.asList("imagedefinitions.json"));
                                                                 }});
                                                             }}))
                                                             .environment(BuildEnvironment.builder()
                                                                                          .buildImage(LinuxBuildImage.STANDARD_2_0)
                                                                                          .privileged(true)
                                                                                          .build())
                                                             .build();

        ecr.grant(lambdaBuild.getGrantPrincipal(),
                  "ecr:InitiateLayerUpload",
                  "ecr:UploadLayerPart",
                  "ecr:CompleteLayerUpload",
                  "ecr:*");

        lambdaBuild.getGrantPrincipal()
                   .addToPolicy(PolicyStatement.Builder.create()
                                                       .actions(Arrays.asList("ecr:GetAuthorizationToken"))
                                                       .resources(Arrays.asList("*"))
                                                       .build());

        Artifact sourceOutput = Artifact.artifact("sourceOutput");
        Artifact buildOutput = Artifact.artifact("buildOutput");
        Pipeline.Builder.create(this, "ImageBuildPipeline")
                        .stages(Arrays.asList(StageProps.builder()
                                                        .stageName("Source")
                                                        .actions(Arrays.asList(GitHubSourceAction.Builder.create()
                                                                                                         .actionName(
                                                                                                                 "GitHub")
                                                                                                         .trigger(
                                                                                                                 GitHubTrigger.WEBHOOK)
                                                                                                         .repo("Raft")
                                                                                                         .owner("brandontoner")
                                                                                                         .output(sourceOutput)
                                                                                                         .oauthToken(
                                                                                                                 SecretValue
                                                                                                                         .plainText(
                                                                                                                                 new String(
                                                                                                                                         Files.readAllBytes(
                                                                                                                                                 Paths.get("/home/brandon/IdeaProjects/Raft/Raft-CDK/github-token")))))
                                                                                                         .build()))
                                                        .build(),
                                              StageProps.builder()
                                                        .stageName("Build")
                                                        .actions(Arrays.asList(CodeBuildAction.Builder.create()
                                                                                                      .actionName(
                                                                                                              "CodeBuild")
                                                                                                      .input(sourceOutput)
                                                                                                      .outputs(Arrays.asList(
                                                                                                              buildOutput))
                                                                                                      .project(
                                                                                                              lambdaBuild)
                                                                                                      .build()))
                                                        .build(),
                                              StageProps.builder()
                                                        .stageName("Deploy")
                                                        .actions(Arrays.asList(EcsDeployAction.Builder.create()
                                                                                                      .actionName("EcsDeploy")
                                                                                                      .service(service)
                                                                                                      .input(buildOutput)
                                                                                                      .build()))
                                                        .build()))
                        .build();
    }
}
