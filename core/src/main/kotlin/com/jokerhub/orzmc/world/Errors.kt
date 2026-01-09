package com.jokerhub.orzmc.world

open class OptimizeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class InputNotDirectoryException(message: String) : OptimizeException(message)
class OutputRequiredException(message: String) : OptimizeException(message)
class OutputNotEmptyException(message: String) : OptimizeException(message)
class OutputAccessDeniedException(message: String, cause: Throwable? = null) : OptimizeException(message, cause)
class CompressionFailedException(message: String, cause: Throwable? = null) : OptimizeException(message, cause)
class InPlaceReplacementException(message: String, cause: Throwable? = null) : OptimizeException(message, cause)
class InvalidWorldStructureException(message: String, cause: Throwable? = null) : OptimizeException(message, cause)
class ForceLoadedParseException(message: String, cause: Throwable? = null) : OptimizeException(message, cause)
class AggregateOptimizeException(message: String, val errors: List<OptimizeError>) : OptimizeException(message)
