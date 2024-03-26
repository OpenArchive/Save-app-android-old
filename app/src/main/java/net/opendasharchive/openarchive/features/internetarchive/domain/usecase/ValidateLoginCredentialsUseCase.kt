package net.opendasharchive.openarchive.features.internetarchive.domain.usecase

class ValidateLoginCredentialsUseCase {

    operator fun invoke(identifier: String, factor: String): Boolean {
        return if (identifier.contains('@')) {
             validateEmail(identifier)
        } else {
            validateUsername(identifier)
        } && validatePassword(factor)
    }

    private fun validateEmail(identifier: String) = identifier.isNotBlank()

    private fun validateUsername(identifier: String) = identifier.isNotBlank()

    private fun validatePassword(factor: String) = factor.isNotBlank()
}
