stage('Monitor'){
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

            FreeIptable = "<table border=1 style='display: inline-block'><tr><th>VPC ID</th><th>Subnet ID</th><th>Utilized IPs</th><th>Max IPs in Subnet </th><th>Utilization %</th></tr>"
            used80Iptable = "<table border=1 style='display: inline-block'><tr><th>VPC ID</th><th>Subnet ID</th><th>Utilized IPs</th><th>Max IPs in Subnet </th><th>Utilization %</th></tr>"

            def vpcTasks = [:]

            vpcs.Vpcs.each { vpc ->
                def vpcId = vpc.VpcId

                vpcTasks["Describe Subnets for ${vpcId}"] = {
                    def subnetsJson = awsexe("ec2 describe-subnets --filters \"Name=vpc-id,Values=${vpcId}\" --region ${params.BLZAWSRegion}")
                    def subnets = readJSON text: subnetsJson

                    subnets.Subnets.each { subnet ->
                        def subnetId = subnet.SubnetId
                        subnetMask = subnet.CidrBlock.split('/')[1]
                        subnetBits = 32 - (subnetMask as Integer)
                        totalIps = 1 << subnetBits
                        def freeIPs = subnet.AvailableIpAddressCount as Integer
                        usedIps = totalIps - freeIPs
                        utilization = ((totalIps - freeIPs) / totalIps) * 100
                        utilizationValue = utilization.toString().split("\\.")[0]

                        def colour = (totalIps * 0.2 > freeIPs) ? "red" : "white"
                        if (totalIps * 0.2 > freeIPs) {
                            used80Iptable += "<tr><th>${vpcId}</th><th>${subnet.SubnetId}</th><td style=\"text-align:center\" bgcolor=\"${colour}\">${usedIps}</th><th>${totalIps}</th><td style=\"text-align:center\" bgcolor=\"${colour}\">${utilizationValue}%</td></tr>"
                        }
                        FreeIptable += "<tr><th>${vpcId}</th><th>${subnet.SubnetId}</th><td style=\"text-align:center\" bgcolor=\"${colour}\">${usedIps}</th><th>${totalIps}</th><td style=\"text-align:center\" bgcolor=\"${colour}\">${utilizationValue}%</td></tr>"
                    }
                }
            }

            parallel vpcTasks

            FreeIptable += "</table>"
            used80Iptable += "</table>"

            allQuotaDetails = readJSON(text: readFile("service-quota-data.json").trim())
            alert = []
            QuotaValue = [:]

            def quotaTasks = [:]

            allQuotaDetails.each { service ->
                quotaTasks["Process Quotas for ${service.ServiceCode}"] = {
                    QuotaValue[service] = [:]
                    service.Quotas.each { quota ->
                        MonitoringRequiredFlag = quota.MonitoringRequired
                        if (params.ApplyCustomList) {
                            MonitoringRequiredFlag = false
                            def serviceDetails = servicestobeTest.find { it.ServiceCode == service.ServiceCode }
                            if (serviceDetails) {
                                def QuotaDetails = serviceDetails.Quotas.find { it.QuotaCode == quota.QuotaCode }
                                if (QuotaDetails) {
                                    QuotaValue[service][quota] = [:]
                                    MonitoringRequiredFlag = true
                                    if (QuotaDetails.CustomLimit) {
                                        QuotaLimit = QuotaDetails.CustomLimit
                                    }
                                }
                            }
                        } else {
                            QuotaValue[service][quota] = [:]
                        }

                        if (MonitoringRequiredFlag) {
                            regionValue = (quota.Region != null ? quota.Region : params.BLZAWSRegion)
                            commandWithRegion = quota.Command.replaceAll("varRegion", params.BLZAWSRegion)

                            def quotaDetails = [:]
                            def actualcount = 0
                            def quotaValue = 0
                            def utilization = 0
                            def utilizationValue = 0
                            def errorFlag = false

                            if (quota.TrustedAdvisor) {
                                try {
                                    retry(5) {
                                        TrustedAdvisorData = awsexe(commandWithRegion)
                                        TrustedAdvisorJSon = readJSON text: TrustedAdvisorData
                                        if (TrustedAdvisorJSon.isEmpty()) {
                                            actualValue = "0"
                                            actualcount = actualValue.toInteger()
                                            quotaDetails = readJSON text: awsexe("service-quotas get-service-quota --region ${regionValue} --service-code ${service.ServiceCode} --quota-code ${quota.QuotaCode}")
                                            quotaValue = quotaDetails.Quota.Value
                                        } else if (quota.Global) {
                                            actualValue = TrustedAdvisorJSon[0].metadata[4]
                                            if (actualValue.contains(".")) {
                                                actualValue = actualValue.toString().split("\\.")[0]
                                            }
                                            actualcount = actualValue.toInteger()
                                            quotaValue = TrustedAdvisorJSon[0].metadata[3].toInteger()
                                        } else {
                                            TrustedAdvisorRegion = TrustedAdvisorJSon.find { it.region == "${regionValue}" }
                                            actualValue = TrustedAdvisorRegion.metadata[4]
                                            if (actualValue.contains(".")) {
                                                actualValue = actualValue.toString().split("\\.")[0]
                                            }
                                            actualcount = actualValue.toInteger()
                                            quotaValue = TrustedAdvisorRegion.metadata[3].toInteger()
                                        }
                                    }
                                } catch (e) {
                                    actualcount = 0
                                    errorFlag = true
                                    quotaDetails = readJSON text: awsexe("service-quotas get-service-quota --region ${regionValue} --service-code ${service.ServiceCode} --quota-code ${quota.QuotaCode}")
                                    quotaValue = quotaDetails.Quota.Value
                                }
                            } else {
                                retry(5) {
                                    quotaDetails = readJSON text: awsexe("service-quotas get-service-quota --region ${regionValue} --service-code ${service.ServiceCode} --quota-code ${quota.QuotaCode}")
                                    quotaValue = quotaDetails.Quota.Value
                                    if (quota.PreCommand) {
                                        preCommandWithRegion = quota.PreCommand.replaceAll("varRegion", params.BLZAWSRegion)
                                        resourceList = awsexe(preCommandWithRegion).trim()
                                        def resources = readJSON text: resourceList
                                        actualcount = resources.collect { resource ->
                                            commandWithResource = commandWithRegion.replaceAll("varResource", resource)
                                            awsexe(commandWithResource).trim().toInteger()
                                        }.max() ?: 0
                                    } else {
                                        actualValue = awsexe(commandWithRegion).trim().toFloat()
                                        actualcount = actualValue.toInteger()
                                    }
                                }
                            }

                            utilization = (actualcount / quotaValue) * 100
                            utilizationValue = utilization.toString().split("\\.")[0].toInteger()

                            QuotaValue[service][quota] = [
                                "ServiceCode": service.ServiceCode,
                                "QuotaName": quota.QuotaName,
                                "actualcount": actualcount,
                                "quotaValue": quotaValue,
                                "utilizationValue": utilizationValue,
                                "errorFlag": errorFlag,
                                "QuotaLimit": QuotaLimit
                            ]
                        }
                    }
                }
            }

            parallel quotaTasks

            def resultAll = ""

            QuotaValue.each { service, quotas ->
                quotas.each { quota, details ->
                    def ServiceCode = details["ServiceCode"]
                    def QuotaName = details["QuotaName"]
                    def actualcount = details["actualcount"]
                    def quotaValue = details["quotaValue"]
                    def utilizationValue = details["utilizationValue"]
                    def errorFlag = details["errorFlag"]
                    def QuotaLimit = details["QuotaLimit"]

                    def column = "<tr><td style=\"text-align:center\">${ServiceCode}</td><td style=\"text-align:center\">${QuotaName}</td><td style=\"text-align:center\">${actualcount}</td><td style=\"text-align:center\">${quotaValue}</td><td style=\"text-align:center\">${utilizationValue}%</td></tr>"

                    if (errorFlag) {
                        alert = true
                        column = "<tr><td style=\"text-align:center\">${ServiceCode}</td><td style=\"text-align:center\">${QuotaName}</td><td style=\"text-align:center\">${actualcount}</td><td style=\"text-align:center\">${quotaValue}</td><td style=\"text-align:center\">${utilizationValue}%</td></tr>"
                    }

                    resultAll += column
                }
            }

            resultAll = "<table border=1 style='display: inline-block'><tr><th>Service Code</th><th>Quota Name</th><th>Used</th><th>Max</th><th>Utilization %</th></tr>" + resultAll + "</table>"

            mail body: "<html><body> <p> <b><u><i> Free IPs in Subnets </i></u></b> </p> ${FreeIptable} <p> <b><u><i> Utilized IPs 80% in Subnets </i></u></b> </p> ${used80Iptable} <p> <b><u><i> Service Quotas </i></u></b> </p> ${resultAll} </body></html>", subject: "Jenkins Pipeline: ${params.StandardCfnOperation} - ${params.accountNo}", to: "your-email@example.com"
        }
    }
}
