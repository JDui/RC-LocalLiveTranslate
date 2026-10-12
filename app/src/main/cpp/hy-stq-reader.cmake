# Generate a narrowly scoped reader adapter without modifying the pinned checkout.
file(READ "${HY_SOURCE}/ggml/src/gguf.cpp" hy_reader)
set(hy_marker "    // read the tensor info")
set(hy_adapter [=[
    // Tencent's fixed 9df5c824 GGUF predates PR #22836's enum renumbering.
    // Only this model profile is accepted by the app's integrity-checked importer.
    const int64_t hy_name = gguf_find_key(ctx, "general.name");
    const int64_t hy_arch = gguf_find_key(ctx, "general.architecture");
    const int64_t hy_ftype = gguf_find_key(ctx, "general.file_type");
    const bool hy_stq_legacy = n_tensors == 354 && hy_name >= 0 && hy_arch >= 0 && hy_ftype >= 0 &&
        gguf_get_kv_type(ctx, hy_name) == GGUF_TYPE_STRING &&
        gguf_get_kv_type(ctx, hy_arch) == GGUF_TYPE_STRING &&
        gguf_get_kv_type(ctx, hy_ftype) == GGUF_TYPE_UINT32 &&
        strcmp(gguf_get_val_str(ctx, hy_name), "HyMT2 1.8B 2bit Stride16") == 0 &&
        strcmp(gguf_get_val_str(ctx, hy_arch), "hunyuan-dense") == 0 &&
        gguf_get_val_u32(ctx, hy_ftype) == 41;
    if (hy_stq_legacy) gguf_set_val_u32(ctx, "general.file_type", 42);
]=])
string(FIND "${hy_reader}" "${hy_marker}" hy_position)
if(hy_position LESS 0)
    message(FATAL_ERROR "Pinned GGUF reader marker changed")
endif()
string(REPLACE "${hy_marker}" "${hy_adapter}\n${hy_marker}" hy_reader "${hy_reader}")
set(hy_read_type "ok = ok && gr.read(info.t.type);")
string(FIND "${hy_reader}" "${hy_read_type}" hy_position)
if(hy_position LESS 0)
    message(FATAL_ERROR "Pinned GGUF type reader changed")
endif()
string(REPLACE "${hy_read_type}" "${hy_read_type}\n            if (hy_stq_legacy && info.t.type == GGML_TYPE_Q2_0) info.t.type = GGML_TYPE_STQ1_0;" hy_reader "${hy_reader}")
set(hy_generated "${CMAKE_CURRENT_BINARY_DIR}/hy-gguf.cpp")
file(WRITE "${hy_generated}" "${hy_reader}")
get_target_property(hy_sources ggml-base SOURCES)
get_target_property(hy_source_dir ggml-base SOURCE_DIR)
set(hy_normalized_sources "")
foreach(hy_source IN LISTS hy_sources)
    if(hy_source STREQUAL "gguf.cpp")
        list(APPEND hy_normalized_sources "${hy_generated}")
    elseif(IS_ABSOLUTE "${hy_source}")
        list(APPEND hy_normalized_sources "${hy_source}")
    else()
        list(APPEND hy_normalized_sources "${hy_source_dir}/${hy_source}")
    endif()
endforeach()
set_property(TARGET ggml-base PROPERTY SOURCES "${hy_normalized_sources}")
target_include_directories(ggml-base PRIVATE "${hy_source_dir}")
