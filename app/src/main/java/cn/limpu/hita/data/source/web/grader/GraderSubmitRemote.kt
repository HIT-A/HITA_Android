package cn.limpu.hita.data.source.web.grader

/**
 * 提交作业所需的远端能力。
 *
 * 抽成接口是为了让仓储层能在纯 JVM 单测里替换成假实现；生产实现是 [GraderWebSource]。
 */
internal interface GraderSubmitRemote {
    fun assignmentDetail(
        entry: GraderSessionPolicy.Entry,
        cookieHeader: String,
        assignmentId: String,
        fallbackTitle: String,
    ): GraderAssignmentDetail

    fun requestUpload(
        entry: GraderSessionPolicy.Entry,
        cookieHeader: String,
        fileName: String,
        assignmentId: String,
    ): GraderUploadTarget

    fun upload(
        entry: GraderSessionPolicy.Entry,
        cookieHeader: String,
        target: GraderUploadTarget,
        file: GraderUploadFile,
        checkActive: () -> Unit = {},
        onProgress: (Long) -> Unit = {},
    )

    fun finishUpload(
        entry: GraderSessionPolicy.Entry,
        cookieHeader: String,
        ident: String,
    )

    fun createSubmission(
        entry: GraderSessionPolicy.Entry,
        cookieHeader: String,
        assignmentId: String,
        ident: String,
    )
}
