stage('Monitor') {
    when {
        expression { params.StandardCfnOperation == 'Monitor_SQ' }
    }
    steps {
        script {
            buildName "#${BUILD_NUMBER}-${params.accountNo}"
            buildDescription "BLZ Account - ${params.accountNo}, Operation - ${params.StandardCfnOperation}, QuotaForServices - ${params.QuotaForServices}"
            
            withCredentials([usernamePassword(credentialsId: "${params.awsCredentialsId}", passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
                generateLoginConfig(env.USERNAME, env.PASSWORD, params.RoleArn)
            }
            awsexe("configure set region ${params.BLZAWSRegion}")

            def vpcJson = awsexe("ec2 describe-vpcs --region ${params.BLZAWSRegion}")
            def vpcs = readJSON text: vpcJson
            FreeIptable = "<table border=1 style='display: inline-block'><tr><th>VPC ID</th><th>Subnet ID</th><th>Utilized IPs</th><th>Max IPs in Subnet</th><th>Utilization %</th></tr>"
            used80Iptable = "<table border=1 style='display: inline-block'><tr><th>VPC ID</th><th>Subnet ID</th><th>Utilized IPs</th><th>Max IPs in Subnet</th><th>Utilization %</th></tr>"

            vpcs.Vpcs.each { vpc ->
                def vpcId = vpc.VpcId
                def subnetsJson = awsexe("ec2 describe-subnets --filters \"Name=vpc-id,Values=${vpcId}\" --region ${params.BLZAWSRegion}")
                def subnets = readJSON text: subnetsJson

                subnets.Subnets.each { subnet ->
                    def subnetId = subnet.SubnetId
                    def subnetMask = subnet.CidrBlock.split('/')[1]
                    def subnetBits = 32 - (subnetMask as Integer)
                    def totalIps = 1 << subnetBits
                    def freeIPs = subnet.AvailableIpAddressCount as Integer
                    def usedIps = totalIps - freeIPs
                    def utilization = ((totalIps - freeIPs) / totalIps) * 100
                    def utilizationValue = utilization.toString().split("\\.")[0]
                    def colour = totalIps * 0.2 > freeIPs ? "red" : "white"
                    def used80IpFlag = colour == "red"

                    used80Iptable += "<tr><th>${vpcId}</th><th>${subnet.SubnetId}</th><td style=\"text-align:center\" bgcolor=\"${colour}\">${usedIps}</th><th>${totalIps}</th><td style=\"text-align:center\" bgcolor=\"${colour}\">${utilizationValue}%</td></tr>"
                    FreeIptable += "<tr><th>${vpcId}</th><th>${subnet.SubnetId}</th><td style=\"text-align:center\" bgcolor=\"${colour}\">${usedIps}</th><th>${totalIps}</th><td style=\"text-align:center\" bgcolor=\"${colour}\">${utilizationValue}%</td></tr>"
                }
            }

            FreeIptable += "</table>"
            used80Iptable += "</table>"

            def allQuotaDetails = readJSON(text: readFile("service-quota-data.json").trim())
            def alert = false
            def parallelTasks = [:]

            allQuotaDetails.each { service ->
                service.Quotas.each { quota ->
                    def taskName = "${service.ServiceCode}-${quota.QuotaCode}"

                    parallelTasks[taskName] = {
                        node {
                            try {
                                def MonitoringRequiredFlag = quota.MonitoringRequired
                                def QuotaLimit = 0.8

                                if (params.ApplyCustomList) {
                                    MonitoringRequiredFlag = false
                                    def serviceDetails = servicestobeTest.find { it.ServiceCode == service.ServiceCode }
                                    if (serviceDetails) {
                                        def QuotaDetails = serviceDetails.Quotas.find { it.QuotaCode == quota.QuotaCode }
                                        if (QuotaDetails) {
                                            MonitoringRequiredFlag = true
                                            if (QuotaDetails.CustomLimit) {
                                                QuotaLimit = QuotaDetails.CustomLimit
                                            }
                                        }
                                    }
                                }

                                if (MonitoringRequiredFlag) {
                                    def regionValue = (quota.Region ?: params.BLZAWSRegion)
                                    def commandWithRegion = quota.Command.replaceAll("varRegion", regionValue)
                                    def actualcount = 0
                                    def errorFlag = false

                                    if (quota.TrustedAdvisor) {
                                        retry(5) {
                                            def TrustedAdvisorData = awsexe(commandWithRegion)
                                            def TrustedAdvisorJSon = readJSON text: TrustedAdvisorData
                                            if (TrustedAdvisorJSon.isEmpty()) {
                                                def quotaDetails = readJSON text: awsexe("service-quotas get-service-quota --region ${regionValue} --service-code ${service.ServiceCode} --quota-code ${quota.QuotaCode}")
                                                def quotaValue = quotaDetails.Quota.Value
                                                actualcount = 0
                                            } else {
                                                def actualValue = quota.Global ? TrustedAdvisorJSon[0].metadata[4] : TrustedAdvisorJSon.find { it.region == "${regionValue}" }.metadata[4]
                                                if (actualValue.contains(".")) {
                                                    actualValue = actualValue.toString().split("\\.")[0]
                                                }
                                                actualcount = actualValue.toInteger()
                                                def quotaValue = quota.Global ? TrustedAdvisorJSon[0].metadata[3].toInteger() : TrustedAdvisorJSon.find { it.region == "${regionValue}" }.metadata[3].toInteger()
                                            }
                                        }
                                    } else {
                                        retry(5) {
                                            def quotaDetails = readJSON text: awsexe("service-quotas get-service-quota --region ${regionValue} --service-code ${service.ServiceCode} --quota-code ${quota.QuotaCode}")
                                            def quotaValue = quotaDetails.Quota.Value
                                            def actualValue = awsexe(commandWithRegion).trim()
                                            actualcount = actualValue.toInteger()
                                            if (quota.PreCommand) {
                                                def preCommandWithRegion = quota.PreCommand.replaceAll("varRegion", regionValue)
                                                def resourceList = awsexe(preCommandWithRegion).trim()
                                                def resources = readJSON text: resourceList
                                                actualcount = resources.collect { resource ->
                                                    def commandWithResource = commandWithRegion.replaceAll("varResource", resource)
                                                    def resourceActualcount = awsexe(commandWithResource).trim().toInteger()
                                                    resourceActualcount
                                                }.max()
                                            }
                                        }
                                    }

                                    def quotaValue = quota.Global ? TrustedAdvisorJSon[0].metadata[3].toInteger() : quotaValue
                                    def utilization = (actualcount / quotaValue) * 100
                                    def utilizationValue = utilization.toString().split("\\.")[0].toInteger()
                                    def column = "<tr><td style=\"text-align:center\">${service.ServiceCode}</td><td style=\"text-align:center\">${quota.QuotaName}</td><td style=\"text-align:center\">${actualcount}</td><td style=\"text-align:center\">${quotaValue}</td><td style=\"text-align:center\">${utilizationValue}%</td></tr>"
                                    def columnConfluence = "<tr><td>${service.ServiceCode}</td><td>${quota.QuotaName}</td><td>${actualcount}</td><td>${quotaValue}</td><td>${utilizationValue}%</td></tr>"

                                    if (errorFlag) {
                                        alert = true
                                        column = "<tr><td style=\"text-align:center\">${service.ServiceCode}</td><td style=\"text-align:center\">${quota.QuotaName}</td><td style=\"text-align:center\" bgcolor=\"yellow\">Not able to fetch data</td><td style=\"text-align:center\">${quotaValue}</td><td style=\"text-align:center\">${utilizationValue}%</td></tr>"
                                    } else if (quotaValue * QuotaLimit < actualcount) {
                                        alert = true
                                        column = "<tr><td style=\"text-align:center\">${service.ServiceCode}</td><td style=\"text-align:center\">${quota.QuotaName}</td><td style=\"text-align:center\" bgcolor=\"red\">${actualcount}</td><td style=\"text-align:center\">${quotaValue}</td><td style=\"text-align:center\" bgcolor=\"red\">${utilizationValue}%</td></tr>"
                                        if (params.SetuEnable) {
                                            def message_payload = "{\"action\": \"create\",\"region\": \"${params.BLZAWSRegion}\",\"env\": \"${params.Environment}\",\"accountNo\": \"${params.accountNo}\",\"service_name\": \"${service.ServiceCode}-${quota.QuotaName}\",\"quota_value\": \"${quotaValue}\",\"actual_value\": \"${actualcount}\"}"
                                            awsexe("lambda invoke --function-name ${params.SNSFunction} --payload '${message_payload}' result.json")
                                        }
                                    }

                                    FreeIptable += column
                                    ConfluenceTableData += columnConfluence
                                }
                            } catch (e) {
                                print("Exception in processing quota: ${e.message}")
                            }
                        }
                    }
                }
            }

            parallel parallelTasks

            FreeIptable += "</table>"
            if (alert) {
                emailext(
                    subject: "Quota Alert: ${params.StandardCfnOperation} - ${params.accountNo}",
                    body: "<p>Following quotas have exceeded the threshold:</p>${FreeIptable}",
                    recipientProviders: [[$class: 'DevelopersRecipientProvider']],
                    attachLog: true
                )
            }
        }
    }
}
