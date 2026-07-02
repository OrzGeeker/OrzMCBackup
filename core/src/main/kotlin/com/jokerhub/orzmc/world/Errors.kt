package com.jokerhub.orzmc.world

/** Base exception for all OrzMCBackup optimization errors. */
open class OptimizeException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** The input path is not a directory. */
class InputNotDirectoryException(
    message: String,
) : OptimizeException(message)

/** Output directory was not specified in non-in-place mode. */
class OutputRequiredException(
    message: String,
) : OptimizeException(message)

/** Output directory exists and is not empty (use --force). */
class OutputNotEmptyException(
    message: String,
) : OptimizeException(message)

/** Output directory is not writable or access was denied. */
class OutputAccessDeniedException(
    message: String,
    cause: Throwable? = null,
) : OptimizeException(message, cause)

/** Failed to compress the output directory. */
class CompressionFailedException(
    message: String,
    cause: Throwable? = null,
) : OptimizeException(message, cause)

/** Failed during in-place replacement. */
class InPlaceReplacementException(
    message: String,
    cause: Throwable? = null,
) : OptimizeException(message, cause)

/** The world directory structure is invalid. */
class InvalidWorldStructureException(
    message: String,
    cause: Throwable? = null,
) : OptimizeException(message, cause)

/** Failed to parse the force-loaded chunk list (chunks.dat). */
class ForceLoadedParseException(
    message: String,
    cause: Throwable? = null,
) : OptimizeException(message, cause)

/** Multiple errors occurred during optimization. */
class AggregateOptimizeException(
    message: String,
    val errors: List<OptimizeError>,
) : OptimizeException(message)
