package com.brandontoner;

import software.amazon.awscdk.core.App;
import software.amazon.awscdk.core.Environment;
import software.amazon.awscdk.core.StackProps;

import java.io.IOException;

public class RaftApp {
    public static void main(final String[] args) throws IOException {
        App app = new App();

        // You have to provide the AWS account if you want the auto detection of the AZs in the region to work
        StackProps stackProps = StackProps.builder()
                                          .env(Environment.builder()
                                                          .region("us-east-1")
                                                          .account("696914731076")
                                                          .build())
                                          .build();

        RaftEcsConstructStack ecs = new RaftEcsConstructStack(app, "RaftEcsConstructStack", stackProps);
        new RaftImageBuildPipeline(app, "RaftImageBuildPipeline", stackProps, ecs.getRepository(), ecs.getService());

        app.synth();
    }

}
