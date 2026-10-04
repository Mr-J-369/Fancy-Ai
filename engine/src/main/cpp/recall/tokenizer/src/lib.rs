use jni::{objects::{JIntArray, JObject, JString}, sys::{jintArray, jlong, jstring}, JNIEnv};
use tokenizers::Tokenizer;

fn report(env: &mut JNIEnv, error: impl std::fmt::Display) {
    let _ = env.throw_new("java/io/IOException", error.to_string());
}

// The Kotlin owner serializes use and close; handles never leave that owner.
#[no_mangle]
pub extern "system" fn Java_com_mrj_fancyai_engine_MemoryTokenizer_create(
    mut env: JNIEnv, _: JObject, path: JString,
) -> jlong {
    let result = (|| -> Result<Tokenizer, Box<dyn std::error::Error + Send + Sync>> {
        let path: String = env.get_string(&path)?.into();
        Tokenizer::from_file(path)
    })();
    match result {
        Ok(tokenizer) => Box::into_raw(Box::new(tokenizer)) as jlong,
        Err(error) => { report(&mut env, error); 0 }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_mrj_fancyai_engine_MemoryTokenizer_encodeNative(
    mut env: JNIEnv, _: JObject, handle: jlong, text: JString,
) -> jintArray {
    let result = (|| -> Result<jintArray, Box<dyn std::error::Error + Send + Sync>> {
        let text: String = env.get_string(&text)?.into();
        let tokenizer = unsafe { &*(handle as *const Tokenizer) };
        let encoding = tokenizer.encode(text, true)?;
        let ids: Vec<i32> = encoding.get_ids().iter().map(|id| *id as i32).collect();
        let array = env.new_int_array(ids.len() as i32)?;
        env.set_int_array_region(&array, 0, &ids)?;
        Ok(array.into_raw())
    })();
    match result {
        Ok(array) => array,
        Err(error) => { report(&mut env, error); std::ptr::null_mut() }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_mrj_fancyai_engine_MemoryTokenizer_decodeNative(
    mut env: JNIEnv, _: JObject, handle: jlong, array: JIntArray,
) -> jstring {
    let result = (|| -> Result<jstring, Box<dyn std::error::Error + Send + Sync>> {
        let mut ids = vec![0i32; env.get_array_length(&array)? as usize];
        env.get_int_array_region(&array, 0, &mut ids)?;
        let ids: Vec<u32> = ids.into_iter().map(|id| id as u32).collect();
        let tokenizer = unsafe { &*(handle as *const Tokenizer) };
        Ok(env.new_string(tokenizer.decode(&ids, true)?)?.into_raw())
    })();
    match result {
        Ok(text) => text,
        Err(error) => { report(&mut env, error); std::ptr::null_mut() }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_mrj_fancyai_engine_MemoryTokenizer_destroy(
    _: JNIEnv, _: JObject, handle: jlong,
) {
    if handle != 0 { unsafe { drop(Box::from_raw(handle as *mut Tokenizer)); } }
}
