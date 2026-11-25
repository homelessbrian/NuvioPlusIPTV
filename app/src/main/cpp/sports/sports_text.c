/* Batched ASCII Sports prefilter; managed code owns Unicode fallback and scoring. */
#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <limits.h>

#define MAX_TERMS 4096
#define MAX_TERM_CHARS 262144
#define MAX_ROWS 256
#define MAX_CHARS 65536
#define MAX_ROW_CHARS 8192

typedef struct { int edge, fail, terminal, output; } Node;
typedef struct { int symbol, target, next; } Edge;
typedef struct Dictionary {
    jlong handle;
    int nodes, edges, capacity;
    Node *node;
    Edge *edge;
    int *length;
    struct Dictionary *next;
} Dictionary;
typedef struct { int row, title_length, description_length; jchar *title, *description; } Result;
static pthread_mutex_t registry_mutex = PTHREAD_MUTEX_INITIALIZER;
static Dictionary *registry;
static uint64_t next_handle = 1;

static void error(JNIEnv *env, const char *type, const char *message) {
    if ((*env)->ExceptionCheck(env)) return;
    jclass cls = (*env)->FindClass(env, type);
    if (cls) { (*env)->ThrowNew(env, cls, message); (*env)->DeleteLocalRef(env, cls); }
}
static void invalid(JNIEnv *env, const char *message) { error(env, "java/lang/IllegalArgumentException", message); }
static void oom(JNIEnv *env) { error(env, "java/lang/OutOfMemoryError", "Bounded native Sports allocation failed"); }
static void destroy_dictionary(Dictionary *d) { if(d){free(d->node);free(d->edge);free(d->length);free(d);} }
static int symbol(jchar c) { if(c==' ')return 0;if(c>='a'&&c<='z')return 1+c-'a';if(c>='0'&&c<='9')return 27+c-'0';return -1; }
static int edge_target(const Dictionary *d,int node,int key) {
    for(int e=d->node[node].edge;e>=0;e=d->edge[e].next)if(d->edge[e].symbol==key)return d->edge[e].target;
    return -1;
}
static void initialize_node(Node *node) { node->edge=-1;node->fail=0;node->terminal=-1;node->output=-1; }
static Dictionary *lookup(jlong handle) { for(Dictionary *d=registry;d;d=d->next)if(d->handle==handle)return d;return NULL; }

JNIEXPORT jlong JNICALL Java_com_nuvio_tv_livetv_sports_SportsTextNative_nativeCreate(JNIEnv *env,jclass cls,jobjectArray terms) {
    (void)cls;
    if(!terms){invalid(env,"Terms cannot be null");return 0;}
    jsize count=(*env)->GetArrayLength(env,terms);
    if(count<1||count>MAX_TERMS){invalid(env,"Use1..4096normalized terms");return 0;}
    int total=0;
    for(jsize i=0;i<count;i++){
        jstring term=(jstring)(*env)->GetObjectArrayElement(env,terms,i);
        if(!term){invalid(env,"Null normalized term");return 0;}
        jsize n=(*env)->GetStringLength(env,term);
        (*env)->DeleteLocalRef(env,term);
        if(n<1||n>MAX_TERM_CHARS-total){invalid(env,"Normalized dictionary exceeds262144characters");return 0;}
        total+=n;
    }
    Dictionary *d=(Dictionary*)calloc(1,sizeof(*d));
    if(!d){oom(env);return 0;}
    d->capacity=total+1;
    d->node=(Node*)malloc((size_t)d->capacity*sizeof(Node));
    d->edge=(Edge*)malloc((size_t)total*sizeof(Edge));
    d->length=(int*)malloc((size_t)count*sizeof(int));
    if(!d->node||!d->edge||!d->length){destroy_dictionary(d);oom(env);return 0;}
    initialize_node(&d->node[0]);d->nodes=1;
    int copied=0;
    for(jsize i=0;i<count;i++){
        jstring term=(jstring)(*env)->GetObjectArrayElement(env,terms,i);
        if(!term){destroy_dictionary(d);invalid(env,"Null normalized term");return 0;}
        jsize n=(*env)->GetStringLength(env,term);
        if(n<1||n>total-copied){
            (*env)->DeleteLocalRef(env,term);destroy_dictionary(d);invalid(env,"Dictionary changed beyond its validated character budget");return 0;
        }
        copied+=n;
        const jchar *text=(*env)->GetStringChars(env,term,NULL);
        if(!text){(*env)->DeleteLocalRef(env,term);destroy_dictionary(d);return 0;}
        d->length[i]=n;
        int current=0,valid=1;
        for(jsize p=0;p<n;p++){
            int key=symbol(text[p]);
            if(key<0||(text[p]==' '&&(p==0||p==n-1||text[p-1]==' '))){valid=0;break;}
            int child=edge_target(d,current,key);
            if(child<0){
                if(d->nodes>=d->capacity||d->edges>=total){valid=0;break;}
                child=d->nodes++;initialize_node(&d->node[child]);
                Edge *edge=&d->edge[d->edges];edge->symbol=key;edge->target=child;edge->next=d->node[current].edge;d->node[current].edge=d->edges++;
            }
            current=child;
        }
        (*env)->ReleaseStringChars(env,term,text);(*env)->DeleteLocalRef(env,term);
        if(!valid||d->node[current].terminal>=0){destroy_dictionary(d);invalid(env,"Terms must be unique normalized lowercase ASCII words/phrases");return 0;}
        d->node[current].terminal=i;
    }
    int *queue=(int*)malloc((size_t)d->nodes*sizeof(int));
    if(!queue){destroy_dictionary(d);oom(env);return 0;}
    int head=0,tail=0;
    for(int e=d->node[0].edge;e>=0;e=d->edge[e].next)queue[tail++]=d->edge[e].target;
    while(head<tail){
        int v=queue[head++];
        for(int e=d->node[v].edge;e>=0;e=d->edge[e].next){
            int child=d->edge[e].target,key=d->edge[e].symbol,f=d->node[v].fail;
            int target=edge_target(d,f,key);
            while(f!=0&&target<0){f=d->node[f].fail;target=edge_target(d,f,key);}
            d->node[child].fail=target<0?0:target;
            int failure=d->node[child].fail;
            d->node[child].output=d->node[failure].terminal>=0?failure:d->node[failure].output;
            queue[tail++]=child;
        }
    }
    free(queue);
    pthread_mutex_lock(&registry_mutex);
    int live=0;for(Dictionary *it=registry;it;it=it->next)live++;
    if(live>=16||next_handle>(uint64_t)INT64_MAX){pthread_mutex_unlock(&registry_mutex);destroy_dictionary(d);invalid(env,"Too many live native dictionaries");return 0;}
    d->handle=(jlong)next_handle++;d->next=registry;registry=d;
    jlong handle=d->handle;
    pthread_mutex_unlock(&registry_mutex);
    return handle;
}

JNIEXPORT void JNICALL Java_com_nuvio_tv_livetv_sports_SportsTextNative_nativeDestroy(JNIEnv *env,jclass cls,jlong handle) {
    (void)cls;
    if(handle==0)return;
    pthread_mutex_lock(&registry_mutex);
    Dictionary **link=&registry;
    while(*link&&(*link)->handle!=handle)link=&(*link)->next;
    Dictionary *d=*link;
    if(d)*link=d->next;
    pthread_mutex_unlock(&registry_mutex);
    if(!d){invalid(env,"Closed or invalid native dictionary handle");return;}
    destroy_dictionary(d);
}

/* Byte-for-byte UTF16 equivalent of current SportsCatalog.asciiNorm. */
static jchar *normalize(const jchar *input,int length,int *output_length) {
    size_t capacity=(size_t)length*4+2;
    jchar *out=(jchar*)malloc(capacity*sizeof(jchar));if(!out)return NULL;
    int n=1,space=1;out[0]=' ';
    for(int i=0;i<length;i++){
        jchar c=input[i];if(c>='A'&&c<='Z')c=(jchar)(c+32);
        if((c>='a'&&c<='z')||(c>='0'&&c<='9')){out[n++]=c;space=0;}
        else if(c=='&'){if(!space)out[n++]=' ';out[n++]='a';out[n++]='n';out[n++]='d';out[n++]=' ';space=1;}
        else if(!space){out[n++]=' ';space=1;}
    }
    if(!space||n==1)out[n++]=' ';
    *output_length=n;return out;
}
static int scan(const Dictionary *d,const jchar *text,int length) {
    int state=0;
    for(int p=0;p<length;p++){
        int key=symbol(text[p]);if(key<0){state=0;continue;}
        int child=edge_target(d,state,key);
        while(state!=0&&child<0){state=d->node[state].fail;child=edge_target(d,state,key);}
        state=child<0?0:child;
        /* No whole-word match can end inside a word. Avoid walking long suffix
           output chains at each character of an unbroken, nonmatching title. */
        int end=p+1;
        if(end>=length||text[end]!=' ')continue;
        int output=d->node[state].terminal>=0?state:d->node[state].output;
        while(output>=0){
            int term=d->node[output].terminal,start=end-d->length[term];
            if(start>0&&text[start-1]==' ')return 1;
            output=d->node[output].output;
        }
    }
    return 0;
}
static void free_results(Result *result,int rows){for(int i=0;i<rows;i++){free(result[i].title);free(result[i].description);}}

JNIEXPORT jobjectArray JNICALL Java_com_nuvio_tv_livetv_sports_SportsTextNative_nativeBatch(
    JNIEnv *env,jclass cls,jlong handle,jcharArray packed,jint used,jintArray offsets,jintArray title_lengths,
    jintArray description_lengths,jint rows) {
    (void)cls;
    if(!packed||!offsets||!title_lengths||!description_lengths||rows<0||rows>MAX_ROWS||used<0||used>MAX_CHARS
        ||used>(*env)->GetArrayLength(env,packed)||rows>(*env)->GetArrayLength(env,offsets)
        ||rows>(*env)->GetArrayLength(env,title_lengths)||rows>(*env)->GetArrayLength(env,description_lengths)){
        invalid(env,"Invalid bounded Sports batch dimensions");return NULL;
    }
    jint start[MAX_ROWS],titles[MAX_ROWS],descriptions[MAX_ROWS];
    (*env)->GetIntArrayRegion(env,offsets,0,rows,start);
    if((*env)->ExceptionCheck(env))return NULL;
    (*env)->GetIntArrayRegion(env,title_lengths,0,rows,titles);
    if((*env)->ExceptionCheck(env))return NULL;
    (*env)->GetIntArrayRegion(env,description_lengths,0,rows,descriptions);
    if((*env)->ExceptionCheck(env))return NULL;
    for(int i=0;i<rows;i++)if(start[i]<0||titles[i]<0||descriptions[i]<0||titles[i]>MAX_ROW_CHARS-descriptions[i]
        ||start[i]>used||titles[i]>used-start[i]||descriptions[i]>used-start[i]-titles[i]){
        invalid(env,"Out-of-range Sports row slice");return NULL;
    }
    jchar *text=(jchar*)malloc((size_t)(used?used:1)*sizeof(jchar));if(!text){oom(env);return NULL;}
    (*env)->GetCharArrayRegion(env,packed,0,used,text);
    if((*env)->ExceptionCheck(env)){free(text);return NULL;}
    for(int i=0;i<used;i++)if(text[i]>=128){free(text);invalid(env,"NonASCII input must use the actual Kotlin fallback");return NULL;}
    Result result[MAX_ROWS];memset(result,0,sizeof(result));int count=0;
    pthread_mutex_lock(&registry_mutex);
    Dictionary *d=lookup(handle);
    if(!d){pthread_mutex_unlock(&registry_mutex);free(text);invalid(env,"Closed or invalid native dictionary handle");return NULL;}
    int failed=0;
    for(int i=0;i<rows;i++){
        int nt_len=0,nd_len=0;
        jchar *nt=normalize(text+start[i],titles[i],&nt_len);
        jchar *nd=normalize(text+start[i]+titles[i],descriptions[i],&nd_len);
        if(!nt||!nd){free(nt);free(nd);failed=1;break;}
        if(!scan(d,nt,nt_len)&&!scan(d,nd,nd_len)){free(nt);free(nd);continue;}
        Result *r=&result[count++];r->row=i;r->title=nt;r->description=nd;r->title_length=nt_len;r->description_length=nd_len;
    }
    pthread_mutex_unlock(&registry_mutex);free(text);
    if(failed){free_results(result,count);oom(env);return NULL;}
    jclass object_class=(*env)->FindClass(env,"java/lang/Object");
    if(!object_class){free_results(result,count);return NULL;}
    jclass string_class=(*env)->FindClass(env,"java/lang/String");
    if(!string_class){free_results(result,count);return NULL;}
    jobjectArray output=(*env)->NewObjectArray(env,3,object_class,NULL);
    if(!output){free_results(result,count);return NULL;}
    jintArray ids=(*env)->NewIntArray(env,count);
    if(!ids){free_results(result,count);return NULL;}
    jobjectArray names=(*env)->NewObjectArray(env,count,string_class,NULL);
    if(!names){free_results(result,count);return NULL;}
    jobjectArray descs=(*env)->NewObjectArray(env,count,string_class,NULL);
    if(!descs){free_results(result,count);return NULL;}
    jint rows_out[MAX_ROWS];
    for(int i=0;i<count;i++){
        rows_out[i]=result[i].row;
        jstring nt=(*env)->NewString(env,result[i].title,result[i].title_length);
        if(!nt){free_results(result,count);return NULL;}
        jstring nd=(*env)->NewString(env,result[i].description,result[i].description_length);
        if(!nd){free_results(result,count);return NULL;}
        (*env)->SetObjectArrayElement(env,names,i,nt);
        if((*env)->ExceptionCheck(env)){free_results(result,count);return NULL;}
        (*env)->SetObjectArrayElement(env,descs,i,nd);
        if((*env)->ExceptionCheck(env)){free_results(result,count);return NULL;}
        (*env)->DeleteLocalRef(env,nt);(*env)->DeleteLocalRef(env,nd);
        if((*env)->ExceptionCheck(env)){free_results(result,count);return NULL;}
    }
    (*env)->SetIntArrayRegion(env,ids,0,count,rows_out);
    if((*env)->ExceptionCheck(env)){free_results(result,count);return NULL;}
    (*env)->SetObjectArrayElement(env,output,0,ids);
    if((*env)->ExceptionCheck(env)){free_results(result,count);return NULL;}
    (*env)->SetObjectArrayElement(env,output,1,names);
    if((*env)->ExceptionCheck(env)){free_results(result,count);return NULL;}
    (*env)->SetObjectArrayElement(env,output,2,descs);
    if((*env)->ExceptionCheck(env)){free_results(result,count);return NULL;}
    free_results(result,count);
    return (*env)->ExceptionCheck(env)?NULL:output;
}
