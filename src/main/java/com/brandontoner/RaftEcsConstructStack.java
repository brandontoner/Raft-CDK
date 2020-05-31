package com.brandontoner;

import software.amazon.awscdk.core.Construct;
import software.amazon.awscdk.core.Stack;
import software.amazon.awscdk.core.StackProps;
import software.amazon.awscdk.services.ec2.Peer;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.ecs.Compatibility;
import software.amazon.awscdk.services.ecs.ContainerDefinitionOptions;
import software.amazon.awscdk.services.ecs.ContainerImage;
import software.amazon.awscdk.services.ecs.FargateService;
import software.amazon.awscdk.services.ecs.PortMapping;
import software.amazon.awscdk.services.ecs.Protocol;
import software.amazon.awscdk.services.ecs.TaskDefinition;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;

import java.util.Arrays;

public class RaftEcsConstructStack extends Stack {
    private final Repository ecr;
    private final FargateService service;

    public RaftEcsConstructStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        this.ecr = Repository.Builder.create(this, "RaftEcrRepository").build();
        // VPC for the ECS cluster. Defaults to all the azs in the VPC
        Vpc vpc = Vpc.Builder.create(this, "RaftEcsVpc").maxAzs(99).build();

        Cluster cluster = Cluster.Builder.create(this, "RaftEcsCluster").vpc(vpc).build();

        // 0.512 GB * $0.004445 per GB per hour + 0.256 vCPU * $0.04048 per vCPU per hour = $9.20 per month per instance
        Role executionRole = Role.Builder.create(this, "RaftEcsTaskExecutionRole")
                                         .assumedBy(ServicePrincipal.Builder.create("ecs-tasks.amazonaws.com").build())
                                         .build();

        executionRole.addToPolicy(PolicyStatement.Builder.create()
                                                         .actions(Arrays.asList("ecr:*"))
                                                         .resources(Arrays.asList("*"))
                                                         .build());
        TaskDefinition taskDefinition = TaskDefinition.Builder.create(this, "RaftEcsTaskDefinition")
                                                              .compatibility(Compatibility.FARGATE)
                                                              .cpu("256")
                                                              .memoryMiB("512")
                                                              .executionRole(executionRole)
                                                              .build();
        taskDefinition.addContainer("web",
                                    ContainerDefinitionOptions.builder()
                                                              .image(ContainerImage.fromRegistry(
                                                                      "amazon/amazon-ecs-sample"))
                                                              .build());
        SecurityGroup securityGroup =
                SecurityGroup.Builder.create(this, "RaftEcsSecurityGroup").allowAllOutbound(true).vpc(vpc).build();
        securityGroup.addIngressRule(Peer.anyIpv4(), Port.tcp(80));
        this.service = FargateService.Builder.create(this, "RaftEcsFargateService")
                                             .cluster(cluster)
                                             .taskDefinition(taskDefinition)
                                             .assignPublicIp(true)
                                             .desiredCount(2)
                                             .securityGroup(securityGroup)
                                             .build();

        this.service.getTaskDefinition()
                    .getDefaultContainer()
                    .addPortMappings(PortMapping.builder()
                                                .containerPort(80)
                                                .hostPort(80)
                                                .protocol(Protocol.TCP)
                                                .build());
    }

    public Repository getRepository() {
        return ecr;
    }

    public FargateService getService() {
        return service;
    }
}
